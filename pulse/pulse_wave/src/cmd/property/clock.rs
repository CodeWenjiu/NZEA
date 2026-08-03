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
