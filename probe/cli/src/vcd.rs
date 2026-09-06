//! VCD signal renaming: maps `probeN` identifiers from an ILA export to semantic
//! names via a map file (one `probeN name` pair per line, `#` comments allowed).

use std::path::Path;

/// Parse a probe map file into `(from, to)` pairs, longest key first so a
/// `probe10`-style key can never be shadowed by a shorter `probe1` prefix.
pub fn load_map(path: &Path) -> std::io::Result<Vec<(String, String)>> {
    let text = std::fs::read_to_string(path)?;
    let mut map = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut it = line.split_whitespace();
        if let (Some(from), Some(to)) = (it.next(), it.next()) {
            map.push((from.to_string(), to.to_string()));
        }
    }
    map.sort_by_key(|a| std::cmp::Reverse(a.0.len()));
    Ok(map)
}

/// Replace every occurrence of each map key with its semantic name. Keys are
/// distinct identifiers, so a plain literal replace is safe for both `$var`
/// declarations and value lines (e.g. `0probe0`, `b1010 probe7`).
pub fn rename_vcd(input: &[u8], map: &[(String, String)]) -> Vec<u8> {
    let mut out = String::from_utf8_lossy(input).into_owned();
    for (from, to) in map {
        out = out.replace(from, to);
    }
    out.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    #[test]
    fn renames_var_declarations_and_value_lines() {
        let vcd =
            b"$var wire 1 probe0 cpu $end\n0probe0\n$var wire 32 probe7 ram $end\nb101 probe7\n";
        let map = vec![
            ("probe0".to_string(), "cpu_reset".to_string()),
            ("probe7".to_string(), "ram_req_addr".to_string()),
        ];
        let out = String::from_utf8(rename_vcd(vcd, &map)).unwrap();
        assert!(out.contains("$var wire 1 cpu_reset cpu $end"));
        assert!(out.contains("0cpu_reset"));
        assert!(out.contains("$var wire 32 ram_req_addr ram $end"));
        assert!(out.contains("b101 ram_req_addr"));
    }

    #[test]
    fn leaves_unmapped_identifiers_untouched() {
        let vcd = b"$var wire 1 probe0 a $end\n$var wire 1 probe3 b $end\n1probe0\n";
        let map = vec![("probe0".to_string(), "cpu_reset".to_string())];
        let out = String::from_utf8(rename_vcd(vcd, &map)).unwrap();
        assert!(out.contains("$var wire 1 probe3 b $end"));
        assert!(out.contains("1cpu_reset"));
    }

    #[test]
    fn parses_map_with_comments_and_longest_key_order() {
        let dir = std::env::temp_dir();
        let path = dir.join("probe_map_test.map");
        let mut f = std::fs::File::create(&path).unwrap();
        writeln!(f, "# semantic probe names").unwrap();
        writeln!(f, "probe10 wide_one").unwrap();
        writeln!(f, "probe1  narrow").unwrap();
        drop(f);

        let map = load_map(&path).unwrap();
        assert_eq!(map.len(), 2);
        assert_eq!(map[0].0, "probe10", "longest key must sort first");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn empty_map_is_harmless() {
        let vcd = b"$var wire 1 probe0 a $end\n";
        let out = rename_vcd(vcd, &[]);
        assert_eq!(out, vcd);
    }
}
