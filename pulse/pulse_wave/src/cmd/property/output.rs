use serde::Serialize;

use crate::SerdeRange;

#[derive(Serialize)]
pub(super) struct PropertyOut {
    pub(super) scope: String,
    pub(super) clock: String,
    pub(super) expr: String,
    #[serde(flatten)]
    pub(super) cycles: SerdeRange<usize>,
    pub(super) total_cycles: usize,
    pub(super) n_cycles: usize,
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
