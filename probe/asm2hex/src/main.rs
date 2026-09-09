//! asm2hex — assemble an RV32IM `.S` file into a NzeaTile boot hex.
//!
//! Drives the same LLVM toolchain (clang → rust-lld → rust-objcopy) that the
//! old `asm2hex.sh` used and converts the raw binary to the tile's
//! `$readmemh`-style hex (one 32-bit word per line, little-endian, linked at
//! 0x8000_0000 by default).
//!
//! Two source modes:
//! - a plain `.S` file path (arbitrary hand-written assembly);
//! - a named built-in test via `--test <NAME>` (the DDR regression programs,
//!   embedded from `tests/` at compile time). Built-in tests are assembled with
//!   `common.S` (shared UART/hex helpers) prepended, so each test .S carries
//!   only its own logic. List them with `--list`.
//!
//! The tile has no C extension, so `-march=rv32im` is mandatory: clang's
//! riscv32 default target enables RVC, and compressed instructions would fault
//! the CPU.

use std::path::{Path, PathBuf};
use std::process::Command;

use clap::Parser;

/// Built-in DDR regression tests: name → (source, one-line description).
/// Sources are embedded at compile time from `tests/`; they do NOT include the
/// shared UART helpers (see `common.S`, prepended by the build step).
const TESTS: &[(&str, &str, &str)] = &[
    (
        "memtest",
        include_str!("../tests/memtest.S"),
        "full DDR3 memtest: write+readback 4KiB, partial writes, overwrite",
    ),
    (
        "small",
        include_str!("../tests/small.S"),
        "small 64-word write+readback (stability A/B)",
    ),
    (
        "wv2",
        include_str!("../tests/wv2.S"),
        "write-verify: write then IMMEDIATELY read back each word",
    ),
    (
        "pwrite",
        include_str!("../tests/pwrite.S"),
        "partial writes (sb/sh) on a single row, dump all 4 words",
    ),
    (
        "pwrite2",
        include_str!("../tests/pwrite2.S"),
        "partial writes on HIGH-half bytes (byte 8, half [12:13])",
    ),
    (
        "focus",
        include_str!("../tests/focus.S"),
        "focused loop over a small region (intermittent-failure probe)",
    ),
    (
        "wr_rd_only",
        include_str!("../tests/wr_rd_only.S"),
        "full-word write + immediate readback only (no partial writes/RMW)",
    ),
];

const COMMON_S: &str = include_str!("../tests/common.S");

#[derive(Parser)]
#[command(
    name = "asm2hex",
    version,
    about = "Assemble an RV32IM .S file (or a built-in test) into a NzeaTile boot hex"
)]
struct Args {
    /// Assembly source (.S) — plain file mode
    #[arg(value_name = "SRC")]
    src: Option<String>,
    /// Output hex file (positional, matching the usage strings and the
    /// justfile recipe: <SRC.S> <OUT.hex> or --test <NAME> <OUT.hex>)
    #[arg(value_name = "OUT")]
    out: Option<PathBuf>,
    /// Use a built-in test instead of a file path (see --list)
    #[arg(long, value_name = "NAME")]
    test: Option<String>,
    /// List available built-in tests and exit
    #[arg(long)]
    list: bool,
    /// Link address (default: 0x8000_0000 — the tile's RAM base)
    #[arg(long, default_value_t = 0x8000_0000)]
    link_addr: u32,
}

fn run(cmd: &mut Command) -> Result<(), String> {
    let status = cmd.status().map_err(|e| {
        format!(
            "failed to spawn {}: {e}",
            cmd.get_program().to_string_lossy()
        )
    })?;
    if !status.success() {
        return Err(format!(
            "{} exited with {status}",
            cmd.get_program().to_string_lossy()
        ));
    }
    Ok(())
}

fn assemble(src_text: &str, link_addr: u32, out: &Path) -> Result<(), String> {
    let tmp = std::env::temp_dir().join(format!("asm2hex-{}", std::process::id()));
    std::fs::create_dir_all(&tmp)
        .map_err(|e| format!("cannot create temp dir {}: {e}", tmp.display()))?;

    // Clean up the temp dir on exit (success or failure).
    struct Cleanup(PathBuf);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    let _cleanup = Cleanup(tmp.clone());

    let src = tmp.join("app.s");
    std::fs::write(&src, src_text).map_err(|e| format!("cannot write temp source: {e}"))?;
    let obj = tmp.join("app.o");
    let elf = tmp.join("app.elf");
    let bin = tmp.join("app.bin");

    // 1. clang: compile RV32IM (no C extension, no relaxation).
    run(Command::new("clang")
        .args([
            "-target",
            "riscv32-unknown-none-elf",
            "-march=rv32im",
            "-mabi=ilp32",
            "-mno-relax",
            "-c",
        ])
        .arg(&src)
        .arg("-o")
        .arg(&obj))?;

    // 2. rust-lld: link at the tile RAM base.
    let linker_script = tmp.join("app.ld");
    std::fs::write(
        &linker_script,
        format!(
            "OUTPUT_ARCH(riscv)\nENTRY(_start)\nSECTIONS {{\n  . = 0x{link_addr:08x};\n  .text : {{ *(.text*) }}\n  .rodata : {{ *(.rodata*) }}\n}}\n"
        ),
    )
    .map_err(|e| format!("cannot write linker script: {e}"))?;
    run(Command::new("rust-lld")
        .args(["-flavor", "gnu", "-T"])
        .arg(&linker_script)
        .arg("-o")
        .arg(&elf)
        .arg(&obj))?;

    // 3. rust-objcopy: extract the raw binary.
    run(Command::new("rust-objcopy")
        .args(["-O", "binary"])
        .arg(&elf)
        .arg(&bin))?;

    // 4. Convert the binary to one 32-bit word per line (little-endian).
    let data = std::fs::read(&bin).map_err(|e| format!("cannot read {}: {e}", bin.display()))?;
    let mut words = String::new();
    for chunk in data.chunks(4) {
        let mut w = [0u8; 4];
        for (i, b) in chunk.iter().enumerate() {
            w[i] = *b;
        }
        words.push_str(&format!("{:08x}\n", u32::from_le_bytes(w)));
    }
    std::fs::write(out, words).map_err(|e| format!("cannot write {}: {e}", out.display()))?;

    let n_words = data.len().div_ceil(4);
    println!(
        "wrote {} ({} words, {} bytes)",
        out.display(),
        n_words,
        data.len()
    );
    Ok(())
}

fn main() -> Result<(), String> {
    let args = Args::parse();

    if args.list {
        println!("Built-in DDR test programs:");
        for (name, _, desc) in TESTS {
            println!("  {name:<10} {desc}");
        }
        println!("\nUse: asm2hex --test <NAME> <out.hex>");
        return Ok(());
    }

    if let Some(name) = args.test.as_deref() {
        // In --test mode the bare output path is the only positional argument,
        // so clap binds it to `src` (positional index 1); map it back to the
        // output slot. Usages: `asm2hex --test <NAME> <OUT.hex>`.
        let out = args
            .out
            .or_else(|| args.src.as_deref().map(PathBuf::from))
            .ok_or_else(|| "missing output path: asm2hex --test <NAME> <out.hex>".to_string())?;
        let Some((_, src, _)) = TESTS.iter().find(|(n, _, _)| *n == name) else {
            let names: Vec<&str> = TESTS.iter().map(|(n, _, _)| *n).collect();
            return Err(format!(
                "unknown test '{name}' — available: {}",
                names.join(", ")
            ));
        };
        // Built-in tests share the UART helpers; the test's _start must be the
        // first word of .text (the CPU boots from the link address), so the
        // helpers are APPENDED after the test body.
        let full = format!("{src}\n{COMMON_S}");
        return assemble(&full, args.link_addr, &out);
    }

    // Plain file mode.
    let (src, out) = match (args.src, args.out) {
        (Some(src), Some(out)) => (src, out),
        _ => {
            return Err(
                "usage: asm2hex <SRC.S> <OUT.hex>  (or --test <NAME> <OUT.hex>)".to_string(),
            )
        }
    };
    let path = Path::new(&src);
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    assemble(&text, args.link_addr, &out)
}
