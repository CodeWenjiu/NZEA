/// If `bin` looks like binary, convert to hex and test whether `filter` is a substring.
pub fn binary_to_hex_contains(bin: &str, filter: &str) -> bool {
    let clean: String = bin.chars().filter(|c| *c == '0' || *c == '1').collect();
    if clean.is_empty() || clean.len() > 128 {
        return false;
    }
    let n = match u128::from_str_radix(&clean, 2) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let hex_lower = format!("{:x}", n);
    let hex_upper = format!("{:X}", n);
    hex_lower.contains(filter) || hex_upper.contains(filter)
}

/// PC hex string (e.g. `80005c9c`) → binary string for name matching in waves.
pub fn pc_hex_to_binary(hex: &str) -> Result<String, Box<dyn std::error::Error>> {
    let h = hex.trim().trim_start_matches("0x").trim_start_matches("0X");
    let v = u32::from_str_radix(h, 16)?;
    Ok(format!("{:032b}", v))
}
