use std::path::Path;

use thiserror::Error;

#[derive(Debug, Error)]
pub enum WaveError {
    #[error("failed to open waveform: {0}")]
    Open(#[from] wellen::WellenError),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("{0}")]
    Parse(String),

    #[error("batch: one or more commands failed (see stderr)")]
    BatchFailed,

    #[error("no waveform file: pass --wave <FILE> or set NZEA_TRACE_FST")]
    NoWaveform,

    #[error("not a readable waveform file: {path}")]
    NotAFile { path: String },

    /// `reason` is the reader's own text and `hint` the likely cause; both are
    /// printed because the reader's diagnostics alone are too terse to act on
    /// (e.g. "failed to load Fst: I/O operation failed").
    #[error("cannot read waveform {path}: {reason}\n  hint: {hint}")]
    Unreadable {
        path: String,
        reason: String,
        hint: String,
    },
}

impl WaveError {
    /// Wrap a reader failure on an existing file. The wording assumes the dump
    /// is at fault rather than the query, which is the case for every failure
    /// seen in practice (the FST reader errors out on EOFs inside a block).
    pub(crate) fn unreadable(path: &Path, reason: impl std::fmt::Display) -> Self {
        Self::Unreadable {
            path: path.display().to_string(),
            reason: reason.to_string(),
            hint: TRUNCATED_HINT.to_string(),
        }
    }
}

/// Advice appended to every reader failure: a dump cut short by a killed
/// writer is by far the most common reason a waveform cannot be parsed, and it
/// is indistinguishable from corruption at this level.
pub(crate) const TRUNCATED_HINT: &str = "the dump is likely truncated — a writer killed mid-run \
     (SIGKILL, or a timeout that escalated) leaves the file without its trailing blocks; \
     rerun it and let it exit cleanly";
