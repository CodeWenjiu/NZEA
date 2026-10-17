//! CLI front-end over the `asm2hex` library (see `lib.rs` for the shared
//! toolchain pipeline, also used by `probe memread`).

use std::path::PathBuf;

use clap::Parser;

use asm2hex::{assemble, COMMON_S, TESTS};

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
        let (n_words, _) = assemble(&full, args.link_addr, &out)?;
        println!("wrote {} ({} words)", out.display(), n_words);
        return Ok(());
    }

    let (src, out) = match (args.src, args.out) {
        (Some(src), Some(out)) => (src, out),
        _ => {
            return Err(
                "usage: asm2hex <SRC.S> <OUT.hex>  (or --test <NAME> <OUT.hex>)".to_string(),
            )
        }
    };
    let path = PathBuf::from(&src);
    let text = std::fs::read_to_string(&path)
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let (n_words, _) = assemble(&text, args.link_addr, &out)?;
    println!("wrote {} ({} words)", out.display(), n_words);
    Ok(())
}
