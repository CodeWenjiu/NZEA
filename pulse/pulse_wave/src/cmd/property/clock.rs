use crate::WaveError;

/// Parse "posedge <signal>" → signal name.
pub(super) fn parse_clock(on: &str) -> Result<&str, WaveError> {
    let s = on.trim();
    let (edge, name) = s
        .split_once(' ')
        .ok_or_else(|| WaveError::Parse("expected 'posedge <signal>'".into()))?;
    if edge != "posedge" {
        return Err(WaveError::Parse(format!(
            "expected 'posedge', got '{}'. negedge not yet supported",
            edge
        )));
    }
    let name = name.trim();
    if name.is_empty() {
        return Err(WaveError::Parse(
            "expected signal name after 'posedge'".into(),
        ));
    }
    Ok(name)
}

/// Parse "FROM-TO" | "FROM-" → (from, to).
pub(super) fn parse_range(s: &str) -> Result<(usize, usize), WaveError> {
    let s = s.trim();
    let (from_str, to_str) = match s.split_once('-') {
        Some((a, b)) => (a.trim(), b.trim()),
        None => {
            return Err(WaveError::Parse(format!(
                "--cycles: expected 'FROM-TO' or 'FROM-', got '{}'",
                s
            )));
        }
    };
    let from: usize = from_str
        .parse()
        .map_err(|_| WaveError::Parse(format!("invalid cycle number: '{}'", from_str)))?;
    if to_str.is_empty() {
        Ok((from, usize::MAX))
    } else {
        let to: usize = to_str
            .parse()
            .map_err(|_| WaveError::Parse(format!("invalid cycle number: '{}'", to_str)))?;
        Ok((from, to))
    }
}
