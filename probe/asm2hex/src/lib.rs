//! asm2hex library — assemble RV32IM `.S` sources into NzeaTile boot hex.
//!
//! Shared by the `asm2hex` CLI and the `probe` tool (which compiles small
//! generated programs, e.g. `memread`, without a separate binary).

use std::path::Path;
use std::process::Command;
use std::sync::atomic::{AtomicU32, Ordering};

/// Built-in DDR regression tests: name → (source, one-line description).
/// Sources are embedded at compile time from `tests/` and do NOT include the
/// shared UART helpers (see [`COMMON_S`], appended by the caller).
pub const TESTS: &[(&str, &str, &str)] = &[
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
    (
        "pwrite_scan",
        include_str!("../tests/pwrite_scan.S"),
        "partial-write scan: 16 rows sb/sh + un-strobed byte preservation",
    ),
    (
        "r5_hammer",
        include_str!("../tests/r5_hammer.S"),
        "row-5-only partial-write hammer (tests if row 5 alone reproduces PVFAIL)",
    ),
    (
        "r5_delay",
        include_str!("../tests/r5_delay.S"),
        "row-5 hammer with 10000-cycle read-back delay (drain check)",
    ),
    (
        "print_only",
        include_str!("../tests/print_only.S"),
        "print-only smoke test, no DDR access",
    ),
    (
        "read_only",
        include_str!("../tests/read_only.S"),
        "pure read-only test: write known data once, repeatedly read back (read-path corruption probe)",
    ),
];

/// Shared UART/debug helpers (putc/putstr/puthex), appended after a test body.
/// The test's own `_start` must be the first word of `.text`, so helpers must
/// come AFTER it in the source text.
pub const COMMON_S: &str = include_str!("../tests/common.S");

/// Assemble `src_text` and write the resulting hex to `out`; the link address
/// is baked into a generated linker script. Returns `(word_count, byte_count)`.
pub fn assemble(src_text: &str, link_addr: u32, out: &Path) -> Result<(usize, usize), String> {
    let tmp = std::env::temp_dir().join(format!("asm2hex-{}", std::process::id()));
    std::fs::create_dir_all(&tmp)
        .map_err(|e| format!("cannot create temp dir {}: {e}", tmp.display()))?;

    // Clean up the temp dir on exit (success or failure).
    struct Cleanup(std::path::PathBuf);
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
    Ok((n_words, data.len()))
}

/// Assemble `src_text` and return the raw words. The temp path is unique per
/// call so concurrent callers (e.g. tests) do not collide.
pub fn assemble_words(src_text: &str, link_addr: u32) -> Result<Vec<u32>, String> {
    static N: AtomicU32 = AtomicU32::new(0);
    let id = N.fetch_add(1, Ordering::Relaxed);
    let out = std::env::temp_dir().join(format!("asm2hex-words-{}-{}", std::process::id(), id));
    let result = assemble(src_text, link_addr, &out).and_then(|_| {
        let text = std::fs::read_to_string(&out)
            .map_err(|e| format!("cannot read {}: {e}", out.display()))?;
        let mut words = Vec::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("//") || line.starts_with('#') {
                continue;
            }
            if let Ok(w) = u32::from_str_radix(line, 16) {
                words.push(w);
            }
        }
        Ok(words)
    });
    let _ = std::fs::remove_file(&out);
    result
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
