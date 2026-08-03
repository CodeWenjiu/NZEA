use serde::Serialize;

use crate::SerdeRange;

#[derive(Serialize)]
pub(super) struct PropertyOut {
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    /// Requested match limit; present when `--max` truncated the output.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) max: Option<usize>,
    pub(super) matches: Vec<SerdeRange<u64>>,
}

impl std::fmt::Display for PropertyOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for ts in &self.matches {
            writeln!(f, "{ts}")?;
        }
        Ok(())
    }
}
