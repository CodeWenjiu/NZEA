//! Probe — FPGA board debug toolbox for NzeaTile.
//!
//! Subcommands:
//! - `load <image> [port] [--addr A] [--read-ms N]`: send a program via the boot
//!   loader's frame protocol, then read back for `--read-ms` ms (default 1000).
//!   The image is either a `$readmemh` hex file (written at `--addr`) or an
//!   ELF32 executable, whose own segments carry the addresses (`--addr` does
//!   not apply): its file bytes go as one contiguous run, so the core is held
//!   for the whole load and starts once, on a complete image.
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

mod elf;
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
    /// Send a program via the boot loader's frame protocol, then read back for --read-ms.
    Load {
        /// Hex words ($readmemh style), or an ELF32 executable.
        image: PathBuf,
        /// Serial port; empty/omitted = auto-discover the CH340 via /dev/serial/by-id.
        #[arg(default_value = "")]
        port: String,
        #[arg(long, default_value_t = 115200)]
        baud: u32,
        /// Address a hex image is written at (hex with 0x prefix, or decimal); an ELF image
        /// carries its own addresses and takes no --addr.
        #[arg(long)]
        addr: Option<String>,
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
        /// Address the command carries (hex with 0x prefix, or decimal).
        #[arg(long, default_value = "0")]
        addr: String,
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
            image,
            port,
            baud,
            addr,
            read_ms,
        } => match addr.as_deref().map(parse_addr).transpose() {
            Ok(a) => run_load(&image, &port, baud, a, read_ms),
            Err(e) => Err(e),
        },
        Cmd::Reset { port, baud, addr } => match parse_addr(&addr) {
            Ok(a) => run_reset(&port, baud, a),
            Err(e) => Err(e),
        },
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

fn resolve_port(port: &str) -> io::Result<String> {
    if port.is_empty() {
        serial::find_serial_port()
    } else {
        Ok(port.to_string())
    }
}

/// The board's reset vector and its RAM window's base (`nzea_fpga/src/Top.scala`: the tile's `ram`
/// window is answered by the DDR3 at this base). The core starts at the reset vector after every
/// reset, whatever an image's own entry says, so an image that does not start here would boot nothing.
const RAM_BASE: u32 = 0x8000_0000;
const RESET_PC: u32 = RAM_BASE;

/// The end of the board's RAM window: 512 MiB of DDR3 above `RAM_BASE`.
const RAM_END: u32 = RAM_BASE + 512 * 1024 * 1024;

fn run_load(path: &Path, port: &str, baud: u32, addr: Option<u32>, read_ms: u64) -> io::Result<()> {
    let (addr, words) = plan_load(path, addr)?;
    let port = resolve_port(port)?;
    send_and_readback(&port, baud, addr, &words, read_ms)
}

/// What a load will send: the address the words go to and the words themselves. The image is either an
/// ELF32 executable (its own addresses; one contiguous run, so the core is released once, on a complete
/// image) or a hex word list (written at `addr`).
fn plan_load(path: &Path, addr: Option<u32>) -> io::Result<(u32, Vec<u32>)> {
    let data = std::fs::read(path)
        .map_err(|e| io::Error::new(e.kind(), format!("reading {}: {e}", path.display())))?;
    let what = path.display();

    if !elf::is_elf(&data) {
        let words = hex::load_words(path)
            .map_err(|e| io::Error::new(e.kind(), format!("reading {}: {e}", path.display())))?;
        if words.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("no words found in {what} (and it is not an ELF image)"),
            ));
        }
        let addr = addr.unwrap_or(0);
        println!("Sending {} words to addr 0x{addr:08X}…", words.len());
        return Ok((addr, words));
    }

    if addr.is_some() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "{what} is an ELF image: its segments carry their own addresses, so --addr does not apply"
            ),
        ));
    }
    let image = elf::parse(&data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("{what}: {e}")))?;
    if image.base != RESET_PC {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "{what}: the image starts at 0x{:08X}, but the board's reset vector is 0x{RESET_PC:08X} — nothing would be loaded there",
                image.base
            ),
        ));
    }
    let end = image.base as u64 + image.bytes.len() as u64;
    if end > RAM_END as u64 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "{what}: the image runs 0x{:08X}..0x{end:08X}, past the board's RAM at 0x{RAM_END:08X}",
                image.base
            ),
        ));
    }
    let words = image.words();
    println!(
        "Loading {what}: {} words ({} bytes) into 0x{:08X}..0x{:08X}…",
        words.len(),
        image.bytes.len(),
        image.base,
        image.base as u64 + image.bytes.len() as u64
    );
    let segments = image
        .segments
        .iter()
        .map(|s| format!("0x{:08X}+{}", s.addr, s.file_bytes))
        .collect::<Vec<_>>()
        .join(", ");
    println!("  image: {segments}");
    if image.entry != RESET_PC {
        println!(
            "  note: the image's entry is 0x{:08X}; the board starts at its reset vector 0x{RESET_PC:08X}",
            image.entry
        );
    }
    if image.empty_segments > 0 {
        println!(
            "  {} segment(s) carry no file bytes (bss/heap/stack): not written",
            image.empty_segments
        );
    }
    if image.padding_bytes > 0 {
        println!("  {} bytes of padding zero-filled", image.padding_bytes);
    }
    Ok((image.base, words))
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

/// How long a monitoring session waits for the first byte of a reset command's answer before repeating it.
const RESET_RETRY_MS: i32 = 600;

/// How many reset commands a monitoring session may send while the line stays silent: the first, plus repeats.
const RESET_SENDS: u32 = 3;

fn run_monitor(port: &str, baud: u32, reset: bool, hex: bool) -> io::Result<()> {
    let port = resolve_port(port)?;
    let mut ser = Serial::open(&port, baud)?;
    let frame = reset.then(|| protocol::reset_frame(0));
    if let Some(frame) = &frame {
        println!("[reset] sending reset command…");
        ser.write_all(frame)?;
        ser.flush()?;
    }
    println!("[monitor] attached to {port} @ {baud} baud (Ctrl-C to stop)");

    let mut stdout = io::stdout().lock();
    // Silence is the only answer a reset command has: the loader sends nothing back, and a frame written right after
    // a load is occasionally lost (measured: roughly one in three, always recovered by sending it again). So while the
    // line is quiet the command is repeated — a duplicate is harmless, because a reset is a reset and the loader
    // resyncs on the magic.
    let mut repeats_left = frame.as_ref().map_or(0, |_| RESET_SENDS - 1);
    let mut gave_up_announced = false;
    loop {
        let timeout = if repeats_left > 0 { RESET_RETRY_MS } else { -1 };
        if !ser.wait_readable(timeout)? {
            match &frame {
                Some(frame) if repeats_left > 0 => {
                    repeats_left -= 1;
                    println!("[reset] nothing yet — repeating the command");
                    ser.write_all(frame)?;
                    ser.flush()?;
                }
                Some(_) if !gave_up_announced => {
                    gave_up_announced = true;
                    println!(
                        "[reset] still nothing after {RESET_SENDS} commands — still listening"
                    );
                }
                _ => {}
            }
            continue;
        }
        repeats_left = 0;
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

/// Parse an address: hex with or without a `0x` prefix (the expected
/// DDR-address form); only a value that is not valid hex falls back to decimal.
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
    send_and_readback(&port, baud, RESET_PC, &words, read_ms)
}

/// Read whatever arrives within `window`, then print it: as text if printable,
/// else as big-endian words.
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

/// Rename VCD identifiers via a probe map, e.g.
/// `probe vcd-rename ila_probes.map capture.vcd out.vcd`.
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

    #[test]
    fn every_address_option_takes_hex_and_decimal() {
        // `load`/`reset` took a `u32`, so `0x…` was a parse error there while `memread` took it.
        let cli = Cli::try_parse_from(["probe", "load", "p.hex", "--addr", "0x80000000"])
            .expect("the loader's address takes a 0x spelling");
        match cli.cmd {
            Cmd::Load { addr, .. } => {
                let addr = addr.expect("the flag was given");
                assert_eq!(parse_addr(&addr).expect("address parses"), 0x8000_0000)
            }
            _ => panic!("expected the load command"),
        }
        assert!(Cli::try_parse_from(["probe", "reset", "--addr", "2147483648"]).is_ok());
        assert!(Cli::try_parse_from(["probe", "memread", "0x88000000"]).is_ok());
        // An image with no address of its own to give: the ELF's segments have them.
        let cli =
            Cli::try_parse_from(["probe", "load", "app.elf"]).expect("an image needs no --addr");
        match cli.cmd {
            Cmd::Load { addr, .. } => assert!(addr.is_none()),
            _ => panic!("expected the load command"),
        }
    }

    /// An ELF32 for RISC-V with one `PT_LOAD` segment: `words` at `addr`, entry `entry`.
    fn elf_at(entry: u32, addr: u32, words: &[u32]) -> Vec<u8> {
        let mut data = vec![0u8; 52];
        data[..4].copy_from_slice(b"\x7fELF");
        data[4] = 1;
        data[5] = 1;
        data[6] = 1;
        data[16..18].copy_from_slice(&2u16.to_le_bytes());
        data[18..20].copy_from_slice(&243u16.to_le_bytes());
        data[24..28].copy_from_slice(&entry.to_le_bytes());
        data[28..32].copy_from_slice(&52u32.to_le_bytes());
        data[42..44].copy_from_slice(&32u16.to_le_bytes());
        data[44..46].copy_from_slice(&1u16.to_le_bytes());
        data.extend_from_slice(&[0u8; 32]);
        let ph = 52;
        let bytes = (words.len() * 4) as u32;
        data[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
        data[ph + 4..ph + 8].copy_from_slice(&(ph as u32 + 32).to_le_bytes()); // p_offset
        data[ph + 8..ph + 12].copy_from_slice(&addr.to_le_bytes()); // p_vaddr
        data[ph + 12..ph + 16].copy_from_slice(&addr.to_le_bytes()); // p_paddr
        data[ph + 16..ph + 20].copy_from_slice(&bytes.to_le_bytes()); // p_filesz
        data[ph + 20..ph + 24].copy_from_slice(&bytes.to_le_bytes()); // p_memsz
        for w in words {
            data.extend_from_slice(&w.to_le_bytes());
        }
        data
    }

    #[test]
    fn an_elf_load_plans_its_own_addresses_and_refuses_addr() {
        let dir = std::env::temp_dir();
        let elf = dir.join("probe_load_test.elf");
        let hex = dir.join("probe_load_test.hex");

        std::fs::write(&elf, elf_at(0x8000_0000, 0x8000_0000, &[0xDEAD_BEEF, 0x13])).unwrap();
        let (addr, words) = plan_load(&elf, None).expect("an ELF plans its own addresses");
        assert_eq!(addr, 0x8000_0000);
        assert_eq!(words, vec![0xDEAD_BEEF, 0x0000_0013]);
        assert!(
            plan_load(&elf, Some(0x8000_0000)).is_err(),
            "--addr is the hex path's"
        );

        // An image that does not start where the core does boots nothing.
        std::fs::write(&elf, elf_at(0x8000_1000, 0x8000_1000, &[0x13])).unwrap();
        let err = plan_load(&elf, None).expect_err("an image off the reset vector is refused");
        assert!(err.to_string().contains("reset vector"), "{err}");

        // A hex image still goes where --addr says (and defaults to 0).
        std::fs::write(&hex, "DEADBEEF\n").unwrap();
        assert_eq!(plan_load(&hex, Some(0x8000_0000)).unwrap().0, 0x8000_0000);
        assert_eq!(plan_load(&hex, None).unwrap().0, 0);

        let _ = std::fs::remove_file(&elf);
        let _ = std::fs::remove_file(&hex);
    }
}
