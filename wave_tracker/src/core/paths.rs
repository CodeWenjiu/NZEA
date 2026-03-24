use std::path::PathBuf;

/// Default path: `chip-dev/remu/target/trace.fst` (nzea and remu are siblings under chip-dev).
pub fn default_wave_path() -> PathBuf {
    let manifest = std::env::var("CARGO_MANIFEST_DIR").unwrap_or_else(|_| ".".into());
    let base = PathBuf::from(&manifest)
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(manifest.clone()));
    base.parent()
        .and_then(|p| p.parent())
        .map(|chip_dev| chip_dev.join("remu").join("target").join("trace.fst"))
        .unwrap_or_else(|| PathBuf::from("../remu/target/trace.fst"))
}
