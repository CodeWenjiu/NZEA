//! Shared cycle/time-base utilities.
//!
//! `property`, `value`, `plot` and `diff` all map between the *cycle domain*
//! (posedges of a clock, the unit users think in) and the *tick domain* (raw
//! waveform timestamps, the unit `value` samples in). The cycle list built by
//! [`build_cycles`] is the single source of truth for both.

use serde::Serialize;
use serde::ser::SerializeStruct;
use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

/// A match point expressed in both time domains at once: the global cycle
/// number (posedge-clock count, the unit `--cycles` uses) and the raw tick.
/// JSON serializes as `{"cycle": 5, "tick": 5531}` so script consumers never
/// have to convert; text renders the cycle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct CycleTick {
    pub(crate) cycle: u64,
    pub(crate) tick: u64,
}

impl Serialize for CycleTick {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        let mut out = s.serialize_struct("CycleTick", 2)?;
        out.serialize_field("cycle", &self.cycle)?;
        out.serialize_field("tick", &self.tick)?;
        out.end()
    }
}

impl std::fmt::Display for CycleTick {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.cycle)
    }
}

/// Build the cycle list for a scope: posedges of `clock_name`, or every
/// time-table entry when the clock signal is absent (ILA exports).
///
/// `clock_required` makes a missing clock an error (this is `property --on`
/// semantics: the user pinned a clock explicitly); otherwise it silently falls
/// back to the sample table (`plot`/`value` semantics).
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
    let (from, to) = window_bounds(all_cycles.len(), cycles.as_ref());
    if from >= all_cycles.len() || from >= to {
        return Err(WaveError::Parse(format!(
            "--cycles {from}-{to}: empty window (trace has {} cycles)",
            all_cycles.len()
        )));
    }
    Ok(all_cycles[from..to].to_vec())
}

/// Resolve a `--cycles` window to `[from, to)`: `~N` takes the last N cycles,
/// open-ended ranges clamp to the trace length, an omitted window covers
/// everything.
pub(crate) fn window_bounds(n_cycles: usize, cycles: Option<&SerdeRange<u64>>) -> (usize, usize) {
    match cycles {
        Some(r) => {
            if let Some(n) = r.tail_n() {
                let n = (n as usize).min(n_cycles);
                (n_cycles - n, n_cycles)
            } else {
                (*r.0.start() as usize, (*r.0.end() as usize).min(n_cycles))
            }
        }
        None => (0, n_cycles),
    }
}

/// Window-local index of the cycle whose tick equals `tick` (binary search;
/// the cycle list is sorted by tick). The cycle list is the authoritative
/// tick↔cycle mapping — never build a second one.
pub(crate) fn tick_position(cycles: &[(usize, u64)], tick: u64) -> Option<usize> {
    cycles.binary_search_by_key(&tick, |&(_ti, t)| t).ok()
}

/// Map a tick-domain match range onto `{cycle, tick}` pairs. `offset` is the
/// global cycle index of the window's first entry, so the produced cycle
/// numbers are global (matching the `--cycles` frame). An unknown tick falls
/// back to using the tick value as the cycle (defensive; evaluator outputs
/// always come from inside the window).
pub(crate) fn map_match(
    offset: usize,
    cycles: &[(usize, u64)],
    r: &SerdeRange<u64>,
) -> SerdeRange<CycleTick> {
    let cs = tick_position(cycles, *r.0.start())
        .map(|i| offset + i)
        .unwrap_or(*r.0.start() as usize) as u64;
    let ce = tick_position(cycles, *r.0.end())
        .map(|i| offset + i)
        .unwrap_or(*r.0.end() as usize) as u64;
    SerdeRange(
        CycleTick {
            cycle: cs,
            tick: *r.0.start(),
        }..=CycleTick {
            cycle: ce,
            tick: *r.0.end(),
        },
    )
}

/// Map tick-domain matches onto **window-local** cycle indices (the frame
/// `diff` compares in, where both windows start at cycle 0).
pub(crate) fn local_cycle_ranges(
    win: &[(usize, u64)],
    matches: &[SerdeRange<u64>],
) -> Vec<SerdeRange<u64>> {
    matches
        .iter()
        .map(|r| {
            let s = tick_position(win, *r.0.start()).unwrap_or(0) as u64;
            let e = tick_position(win, *r.0.end()).unwrap_or(0) as u64;
            SerdeRange(s..=e)
        })
        .collect()
}
