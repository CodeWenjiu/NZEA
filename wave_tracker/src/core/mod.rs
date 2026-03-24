//! Shared primitives: time indexing, paths, string helpers, waveform snapshots.

pub mod paths;
pub mod query;
pub mod range;
pub mod strings;
pub mod time;

pub use paths::default_wave_path;
pub use query::{snapshot_at, val_by_substring};
pub use range::for_each_sample_in_range;
pub use strings::{binary_to_hex_contains, pc_hex_to_binary};
pub use time::find_time_idx_at_or_before;
