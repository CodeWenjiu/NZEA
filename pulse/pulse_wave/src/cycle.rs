//! Shared cycle/time-base utilities.
//!
//! `property`, `value` and `plot` all need the same mapping between the
//! *cycle domain* (posedges of a clock, the unit users think in) and the
//! *tick domain* (raw waveform timestamps, the unit `value` samples in).
//! The cycle list is the single source of truth: build it once per
//! (waveform, scope, clock) via [`build_cycles`] and slice it with
//! [`slice_cycles`]; tick↔cycle lookups fall out of the list (`(tt_idx, tick)`
//! pairs, index = global cycle number).

use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

/// Build the cycle list for a scope: posedges of `clock_name`, or every
/// time-table entry when the clock signal is absent (ILA exports).
///
/// The clock signal (when present) is loaded into the waveform. When
/// `clock_required` is set, a missing clock is an error (this is
/// `property --on` semantics: the user pinned a clock explicitly);
/// otherwise a missing clock silently falls back to the sample table
/// (`plot`/`value` semantics).
pub(crate) fn build_cycles(
    wav: &mut wellen::simple::Waveform,
    scope_path: &str,
    clock_name: Option<&str>,
    clock_required: bool,
) -> Result<Vec<(usize, u64)>, WaveError> {
    let h = wav.hierarchy();
    let clock_name = match clock_name {
        Some(n) => n,
        None => {
            // No clock requested: every time-table entry is one cycle.
            let tt = wav.time_table();
            return Ok(tt.iter().enumerate().map(|(ti, &t)| (ti, t)).collect());
        }
    };

    let clock_sig: Option<SignalRef> =
        match crate::cmd::hierarchy::resolve_signals(h, scope_path, &[clock_name.to_string()]) {
            Ok(m) => m.get(clock_name).copied(),
            Err(_) => None,
        };
    let Some(cs) = clock_sig else {
        if clock_required {
            return Err(WaveError::Parse(format!(
                "clock signal '{clock_name}' not found in scope '{scope_path}'"
            )));
        }
        let tt = wav.time_table();
        return Ok(tt.iter().enumerate().map(|(ti, &t)| (ti, t)).collect());
    };

    wav.load_signals(&[cs]);
    let sig = wav.get_signal(cs).expect("clock signal not loaded");
    let tt = wav.time_table();
    let mut out = Vec::new();
    let mut prev = false;
    for (ti, &t) in tt.iter().enumerate() {
        let v = crate::read::read_bit(sig, ti as u32);
        if !prev && v {
            out.push((ti, t));
        }
        prev = v;
    }
    Ok(out)
}

/// Slice the full cycle list per a `--cycles` window (same semantics as
/// `property`/`plot`/`value`). Errors on an empty window.
pub(crate) fn slice_cycles(
    all_cycles: &[(usize, u64)],
    cycles: Option<SerdeRange<u64>>,
) -> Result<Vec<(usize, u64)>, WaveError> {
    let (from, to) = match cycles {
        Some(r) => {
            if let Some(n) = r.tail_n() {
                let n = (n as usize).min(all_cycles.len());
                (all_cycles.len() - n, all_cycles.len())
            } else {
                (
                    *r.0.start() as usize,
                    (*r.0.end() as usize).min(all_cycles.len()),
                )
            }
        }
        None => (0, all_cycles.len()),
    };
    if from >= all_cycles.len() || from >= to {
        return Err(WaveError::Parse(format!(
            "--cycles {from}-{to}: empty window (trace has {} cycles)",
            all_cycles.len()
        )));
    }
    Ok(all_cycles[from..to].to_vec())
}
