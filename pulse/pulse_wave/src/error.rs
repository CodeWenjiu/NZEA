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
}
