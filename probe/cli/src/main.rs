//! Probe — FPGA board debug toolbox for NzeaTile.
//!
//! Subcommands:
//! - `load <hex> [port] [--read-ms N]`: send a program via the BootFsm boot
//!   protocol, then read back for `--read-ms` ms (default 1000).
//! - `reset [port]`: send the reset-only command (magic + addr + size=0): the
//!   CPU restarts from the reset vector with RAM preserved.
//! - `monitor [port] [--reset]`: stream RX bytes with timestamps until Ctrl-C;
//!   with `--reset` the reset command is sent right after attaching, so no boot
//!   output is missed.
//! - `memread <addr> [port] [--read-ms N]`: generate a tiny RV32IM program that
//!   reads the word at `<addr>` (e.g. 0x88000000 for DDR base) and prints it via
//!   puthex, assemble it with the asm2hex library, then load and read back.
//!   Distinguishes "stale residue" from "write failed" without touching RTL.
//!
//! When the port argument is empty or omitted, the CH340 UART is discovered
//! automatically via the stable `/dev/serial/by-id` alias (see `serial.rs`).

mod hex;
mod protocol;
mod serial;
mod vcd;

use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use clap::{Parser, Subcommand};

use crate::serial::Serial;

#[derive(Parser)]
#[command(
    name = "probe",
    version,
    about = "FPGA board debug toolbox for NzeaTile (load / reset / monitor / memread)"
)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Send a hex program via the BootFsm boot protocol, then read back for --read-ms.
    Load {
        hex: PathBuf,
        /// Serial port; empty/omitted = auto-discover the CH340 via /dev/serial/by-id.
        #[arg(default_value = "")]
        port: String,
        #[arg(long, default_value_t = 115200)]
        baud: u32,
        #[arg(long, default_value_t = 0)]
        addr: u32,
        /// Read-back window in ms after the transfer completes.
        #[arg(long, default_value_t = 1000)]
        read_ms: u64,
    },
    /// Reset-only command: CPU restarts from the reset vector, RAM is kept.
    Reset {
        /// Serial port; empty/omitted = auto-discover the CH340 via /dev/serial/by-id.
        #[arg(default_value = "")]
        port: String,
        #[arg(long, default_value_t = 115200)]
        baud: u32,
        #[arg(long, default_value_t = 0)]
        addr: u32,
    },
    /// Stream RX bytes with timestamps until Ctrl-C. With --reset, reset right after attaching.
    Monitor {
        /// Serial port; empty/omitted = auto-discover the CH340 via /dev/serial/by-id.
        #[arg(default_value = "")]
        port: String,
        #[arg(long, default_value_t = 115200)]
        baud: u32,
        #[arg(long)]
        reset: bool,
        #[arg(long)]
        hex: bool,
    },
    /// Read the word at <addr> (e.g. DDR base 0x88000000) via a generated tile program.
    #[command(name = "memread")]
    MemRead {
        /// Address to read (hex with 0x prefix, or decimal).
        addr: String,
        /// Serial port; empty/omitted = auto-discover the CH340 via /dev/serial/by-id.
        #[arg(default_value = "")]
        port: String,
        #[arg(long, default_value_t = 115200)]
        baud: u32,
        /// Read-back window in ms after the transfer completes.
        #[arg(long, default_value_t = 1000)]
        read_ms: u64,
    },
    /// Rename VCD signal identifiers via a probe map (`probeN name` per line).
    VcdRename {
        map: PathBuf,
        input: PathBuf,
        output: PathBuf,
    },
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.cmd {
        Cmd::Load {
            hex,
            port,
            baud,
            addr,
            read_ms,
        } => run_load(&hex, &port, baud, addr, read_ms),
        Cmd::Reset { port, baud, addr } => run_reset(&port, baud, addr),
        Cmd::Monitor {
            port,
            baud,
            reset,
            hex,
        } => run_monitor(&port, baud, reset, hex),
        Cmd::MemRead {
            addr,
            port,
            baud,
            read_ms,
        } => match parse_addr(&addr) {
            Ok(a) => run_memread(a, &port, baud, read_ms),
            Err(e) => Err(e),
        },
        Cmd::VcdRename { map, input, output } => run_vcd_rename(&map, &input, &output),
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

/// Resolve the port argument: empty string → auto-discover the board UART.
fn resolve_port(port: &str) -> io::Result<String> {
    if port.is_empty() {
        serial::find_serial_port()
    } else {
        Ok(port.to_string())
    }
}

fn run_load(hex: &Path, port: &str, baud: u32, addr: u32, read_ms: u64) -> io::Result<()> {
    let port = resolve_port(port)?;
    let words = hex::load_words(hex)
        .map_err(|e| io::Error::new(e.kind(), format!("reading {}: {e}", hex.display())))?;
    if words.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("no words found in {}", hex.display()),
        ));
    }

    println!("Sending {} words to addr 0x{addr:08X}…", words.len());
    send_and_readback(&port, baud, addr, &words, read_ms)
}

fn run_reset(port: &str, baud: u32, addr: u32) -> io::Result<()> {
    let port = resolve_port(port)?;
    let frame = protocol::reset_frame(addr);
    println!("Sending reset command (magic + addr=0x{addr:08X} + size=0)…");
    let mut ser = Serial::open(&port, baud)?;
    ser.write_all(&frame)?;
    ser.flush()?;

    let bits = 12 * 10;
    std::thread::sleep(Duration::from_secs_f64(bits as f64 / baud as f64 + 0.5));

    println!("Response:");
    readback(&mut ser, Duration::from_secs(1))?;
    Ok(())
}

fn run_monitor(port: &str, baud: u32, reset: bool, hex: bool) -> io::Result<()> {
    let port = resolve_port(port)?;
    let mut ser = Serial::open(&port, baud)?;
    if reset {
        let frame = protocol::reset_frame(0);
        println!("[reset] sending reset command…");
        ser.write_all(&frame)?;
        ser.flush()?;
    }
    println!("[monitor] attached to {port} @ {baud} baud (Ctrl-C to stop)");

    let mut stdout = io::stdout().lock();
    loop {
        if !ser.wait_readable(-1)? {
            continue;
        }
        let mut buf = [0u8; 512];
        let n = ser.read(&mut buf)?;
        if n == 0 {
            continue;
        }
        let ts = timestamp();
        if hex {
            for b in &buf[..n] {
                writeln!(stdout, "{ts} {b:02x}")?;
            }
        } else {
            // Single write per chunk so the timestamp and payload cannot be
            // split across output buffers.
            let line = format!("{ts} {}\n", String::from_utf8_lossy(&buf[..n]));
            stdout.write_all(line.as_bytes())?;
        }
        stdout.flush()?;
    }
}

/// Send a program frame, wait for it to drain, then read the response window.
fn send_and_readback(
    port: &str,
    baud: u32,
    addr: u32,
    words: &[u32],
    read_ms: u64,
) -> io::Result<()> {
    let frame = protocol::load_frame(addr, words);
    let mut ser = Serial::open(port, baud)?;
    ser.write_all(&frame)?;
    ser.flush()?;

    // Wait for transmission + FPGA processing, then read back the window.
    let bits = (12 + words.len() as u64 * 4) * 10;
    std::thread::sleep(Duration::from_secs_f64(bits as f64 / baud as f64 + 0.5));

    println!("Response:");
    readback(&mut ser, Duration::from_millis(read_ms))?;
    Ok(())
}

/// Generate a tiny RV32IM program that reads `addr` and prints it via puthex.
///
/// Uses only the shared UART helpers from asm2hex's COMMON_S (appended after
/// our `_start`), so the program is self-contained and exercises the real bus.
/// s1 keeps the address across the puthex calls (puthex clobbers t-regs + s0).
fn memread_words(addr: u32) -> Result<Vec<u32>, String> {
    let src = format!(
        ".section .text\n\
         .globl _start\n\
         _start:\n\
         \tli\ts1, 0x{addr:08x}\n\
         \tmv\ta0, s1\n\
         \tjal\tputhex\n\
         \tli\ta1, ' '\n\
         \tjal\tputc\n\
         \tlw\ta0, 0(s1)\n\
         \tjal\tputhex\n\
         \tli\ta1, '\\n'\n\
         \tjal\tputc\n\
         \tlui\tt0, 0x00100\n\
         \tsw\tx0, 0(t0)\n\
         1:\tj\t1b\n",
        addr = addr
    );
    asm2hex::assemble_words(&format!("{src}\n{}", asm2hex::COMMON_S), 0x8000_0000)
}

/// Accepts `0x...` hex (the expected DDR-address form) or plain decimal.
fn parse_addr(s: &str) -> io::Result<u32> {
    let s = s.trim().trim_start_matches("0x").trim_start_matches("0X");
    u32::from_str_radix(s, 16)
        .or_else(|_| s.parse::<u32>())
        .map_err(|e| {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("invalid address '{s}': {e}"),
            )
        })
}

fn run_memread(addr: u32, port: &str, baud: u32, read_ms: u64) -> io::Result<()> {
    let port = resolve_port(port)?;
    let words = memread_words(addr).map_err(io::Error::other)?;
    println!(
        "Reading addr 0x{addr:08X} via a generated tile probe program ({} words)…",
        words.len()
    );
    send_and_readback(&port, baud, 0, &words, read_ms)
}

/// Read whatever arrives within `window`, then print it: text if printable, else big-endian words.
fn readback(ser: &mut Serial, window: Duration) -> io::Result<()> {
    let deadline = Instant::now() + window;
    let mut buf = Vec::new();
    while Instant::now() < deadline {
        if ser.wait_readable(100)? {
            let mut chunk = [0u8; 512];
            let n = ser.read(&mut chunk)?;
            if n > 0 {
                buf.extend_from_slice(&chunk[..n]);
            }
        }
    }
    if buf.is_empty() {
        println!("  (no data)");
        return Ok(());
    }
    if buf
        .iter()
        .all(|&c| (0x20..0x7f).contains(&c) || c == 0x0a || c == 0x0d)
    {
        let text = String::from_utf8_lossy(&buf);
        println!("  {}", text.trim_end());
    } else {
        for (i, w) in buf.as_chunks::<4>().0.iter().enumerate() {
            let word = u32::from_be_bytes(*w);
            println!("  word[{i:2}] = 0x{word:08X}");
        }
    }
    Ok(())
}

/// Rename VCD identifiers via a probe map, e.g. `probe vcd-rename ila_probes.map capture.vcd out.vcd`.
fn run_vcd_rename(map: &Path, input: &Path, output: &Path) -> io::Result<()> {
    let entries = vcd::load_map(map)
        .map_err(|e| io::Error::new(e.kind(), format!("reading {}: {e}", map.display())))?;
    let data = std::fs::read(input)
        .map_err(|e| io::Error::new(e.kind(), format!("reading {}: {e}", input.display())))?;
    let renamed = vcd::rename_vcd(&data, &entries);
    std::fs::write(output, &renamed)
        .map_err(|e| io::Error::new(e.kind(), format!("writing {}: {e}", output.display())))?;
    println!(
        "Renamed {} probes: {} -> {}",
        entries.len(),
        input.display(),
        output.display()
    );
    Ok(())
}

/// Local wallclock `HH:MM:SS.mmm` prefix for monitor output.
fn timestamp() -> String {
    let now = std::time::SystemTime::now();
    let epoch = now
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    let secs = epoch.as_secs() as libc::time_t;
    let millis = epoch.subsec_millis();
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    unsafe {
        libc::localtime_r(&secs, &mut tm);
    }
    format!(
        "[{:02}:{:02}:{:02}.{:03}]",
        tm.tm_hour, tm.tm_min, tm.tm_sec, millis
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memread_program_assembles_and_fits() {
        // The generated probe program must assemble and stay small enough to
        // load (a handful of instructions + helpers).
        let words = memread_words(0x8800_0000).expect("memread program assembles");
        assert!(!words.is_empty());
        assert!(words.len() < 64, "memread program unexpectedly large");
    }
}
