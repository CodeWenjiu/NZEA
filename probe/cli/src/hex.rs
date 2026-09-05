//! $readmemh-style hex file parsing (one word per line, `//`/`#` comments).

use std::path::Path;

pub fn load_words(path: &Path) -> std::io::Result<Vec<u32>> {
    let text = std::fs::read_to_string(path)?;
    let mut words = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("//") || line.starts_with('#') {
            continue;
        }
        for token in line.split_whitespace() {
            if let Ok(w) = u32::from_str_radix(token, 16) {
                words.push(w);
            }
        }
    }
    Ok(words)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    #[test]
    fn parses_hex_words_and_skips_comments() {
        let dir = std::env::temp_dir();
        let path = dir.join("probe_hex_test.hex");
        let mut f = std::fs::File::create(&path).unwrap();
        writeln!(f, "// comment").unwrap();
        writeln!(f, "DEADBEEF 00000013").unwrap();
        writeln!(f, "# another comment").unwrap();
        writeln!(f, "CAFEBABE").unwrap();
        drop(f);

        let words = load_words(&path).unwrap();
        assert_eq!(words, vec![0xDEAD_BEEF, 0x0000_0013, 0xCAFE_BABE]);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn empty_file_yields_no_words() {
        let dir = std::env::temp_dir();
        let path = dir.join("probe_empty_test.hex");
        std::fs::write(&path, "").unwrap();
        assert!(load_words(&path).unwrap().is_empty());
        let _ = std::fs::remove_file(&path);
    }
}
