//! Snapshot of signal values at one time sample; substring lookup by signal name.

use wellen::{SignalRef, simple::Waveform};

/// All `(full_name, value_string)` at sample index `idx` for preloaded signals.
pub fn snapshot_at(
    wf: &Waveform,
    sigs: &[(String, SignalRef)],
    sample_idx: u32,
) -> Vec<(String, String)> {
    let mut vals = Vec::with_capacity(sigs.len());
    for (name, sig_ref) in sigs {
        if let Some(sig) = wf.get_signal(*sig_ref)
            && let Some(offset) = sig.get_offset(sample_idx)
        {
            let val = sig.get_value_at(&offset, 0).to_string();
            vals.push((name.clone(), val));
        }
    }
    vals
}

/// First value whose signal full name contains `name_contains`.
pub fn val_by_substring(vals: &[(String, String)], name_contains: &str) -> Option<String> {
    vals.iter()
        .find(|(n, _)| n.contains(name_contains))
        .map(|(_, v)| v.clone())
}
