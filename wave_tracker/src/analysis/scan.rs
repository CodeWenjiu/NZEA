use crate::core::binary_to_hex_contains;
use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Scan time range, print (time, value) when any matching signal changes.
/// If filter_value is Some, only print when any signal's value string contains the filter.
/// If filter_rd_index is Some, only print when a signal named *rd_index* has value matching the filter.
pub fn scan_time_range(
    wf: &mut Waveform,
    to_show: &[(String, SignalRef)],
    time_table: &[Time],
    start: Time,
    end: Time,
    filter_value: Option<&str>,
    filter_rd_index: Option<&str>,
) {
    if to_show.is_empty() {
        return;
    }

    let to_load: Vec<SignalRef> = to_show.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let mut last_vals: Vec<String> = vec!["?".into(); to_show.len()];
    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;
        let mut changed = false;
        let mut vals = Vec::with_capacity(to_show.len());
        for (j, (_name, sig_ref)) in to_show.iter().enumerate() {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    if last_vals.get(j) != Some(&val) {
                        changed = true;
                    }
                    vals.push(val);
                }
            }
        }
        if changed {
            let matches_value = filter_value.map_or(true, |fv| {
                vals.iter().any(|v| {
                    v.contains(fv)
                        || (v
                            .chars()
                            .all(|c| c == '0' || c == '1' || c == 'x' || c == 'z')
                            && binary_to_hex_contains(v, fv))
                })
            });
            let matches_rd_index = filter_rd_index.map_or(true, |fri| {
                to_show
                    .iter()
                    .zip(vals.iter())
                    .any(|((name, _), v)| name.contains("commit_bits_rd_index") && v == fri)
            });
            if matches_value && matches_rd_index {
                println!("t={} idx={}", t, idx);
                for (j, (name, _)) in to_show.iter().enumerate() {
                    if let Some(v) = vals.get(j) {
                        println!("  {}: {}", name, v);
                    }
                }
            }
            for (j, v) in vals.iter().enumerate() {
                if let Some(lv) = last_vals.get_mut(j) {
                    *lv = v.clone();
                }
            }
        }
    }
}
