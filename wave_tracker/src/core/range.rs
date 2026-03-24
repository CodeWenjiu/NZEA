//! Iterate a loaded waveform over `[start, end]` with one snapshot per time step.

use wellen::{SignalRef, Time, simple::Waveform};

use super::query::snapshot_at;

/// Load `sigs`, then for each sample in `[start, end]` call `f(time, sample_idx, snapshot)`.
pub fn for_each_sample_in_range<F>(
    wf: &mut Waveform,
    time_table: &[Time],
    sigs: &[(String, SignalRef)],
    start: Time,
    end: Time,
    mut f: F,
) -> Result<(), Box<dyn std::error::Error>>
where
    F: FnMut(Time, u32, &[(String, String)]) -> Result<(), Box<dyn std::error::Error>>,
{
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);
    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;
        let snap = snapshot_at(wf, sigs, idx);
        f(t, idx, &snap)?;
    }
    Ok(())
}
