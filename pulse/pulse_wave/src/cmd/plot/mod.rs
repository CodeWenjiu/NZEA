//! `pulse plot` — render a waveform window as SVG.
//!
//! Samples signal values per cycle (posedges of `clock`, or every time-table
//! entry when the scope has no clock — same convention as `property`) and
//! renders a step waveform with optional event mark lines (`--mark EXPR`,
//! repeatable, same expression language as `--eval`).
//!
//! The wave-preparation helpers here (`prepare_wave`, `sample`,
//! `eval_matches`) are shared with `pulse diff`, which compares two waveforms
//! under the same cycle/clock convention.

use std::collections::BTreeMap;
use std::io::Write;
use std::path::Path;

use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

mod svg;

use self::svg::PlotData;
pub(crate) use self::svg::Val;

/// Resolved signals + full cycle list for one waveform (shared by plot/diff).
pub(crate) struct PreparedWave {
    /// Requested signals, by name (same order-independent map as `value`).
    pub name_to_sref: BTreeMap<String, wellen::SignalRef>,
    /// Full cycle list: posedges of `clock`, or every time-table entry when
    /// the scope has no clock (ILA exports).
    pub cycles: Vec<(usize, u64)>,
}

/// Resolve `signal_names` in `scope_path`, detect the clock, load the signal
/// data and build the cycle list. `--cycles` windows are sliced from `cycles`
/// by the caller.
pub(crate) fn prepare_wave(
    wav: &mut wellen::simple::Waveform,
    scope_path: &str,
    signal_names: &[String],
) -> Result<PreparedWave, WaveError> {
    let h = wav.hierarchy();

    // Scope + signal resolution (same as `value`).
    let name_to_sref = crate::cmd::hierarchy::resolve_signals(h, scope_path, signal_names)?;

    let srefs: Vec<SignalRef> = name_to_sref.values().copied().collect();
    wav.load_signals(&srefs);

    // Cycle list: posedges of `clock`, or every tt entry (no clock).
    let cycles = crate::cycle::build_cycles(wav, scope_path, Some("clock"), false)?;

    Ok(PreparedWave {
        name_to_sref,
        cycles,
    })
}

/// Slice the full cycle list per `--cycles` (same window semantics as
/// `property`/`plot`/`value`). Errors on an empty window.
pub(crate) use crate::cycle::slice_cycles;

/// 1-bit value (false on zero/X).
pub(crate) use crate::read::read_bit;

/// Multi-bit value; None on X/Z or non-vector.
pub(crate) use crate::read::read_u64;

/// Classify a signal sample into a plot/diff value.
pub(crate) fn sample(sig: &wellen::Signal, tt_idx: u32) -> Val {
    let Some(off) = sig.get_offset(tt_idx) else {
        return Val::Unknown;
    };
    let v = sig.get_value_at(&off, 0);
    match v {
        wellen::SignalValueRef::BitVec(bv) if bv.width() == 1 => match bv.get_bit(0).as_ascii() {
            '1' => Val::High,
            '0' => Val::Low,
            _ => Val::Unknown,
        },
        other => match u64::try_from(other) {
            Ok(n) => Val::Vector(n),
            Err(_) => Val::Unknown,
        },
    }
}

/// Evaluate one expression over a window (tick matches, not yet mapped back
/// to cycle indices). Shared by plot's `--mark` and diff's `--eval`.
pub(crate) fn eval_matches(
    wav: &wellen::simple::Waveform,
    win: &[(usize, u64)],
    name_to_sref: &BTreeMap<String, SignalRef>,
    expr: &str,
) -> Result<Vec<SerdeRange<u64>>, WaveError> {
    let ast = crate::cmd::property::expr::parser::parse(expr)?;
    crate::cmd::property::expr::stdlib::check_functions(&ast).map_err(WaveError::Parse)?;
    let ast = crate::cmd::property::expr::stdlib::desugar(&ast);

    let signal_sigs: BTreeMap<String, &wellen::Signal> = name_to_sref
        .iter()
        .map(|(n, sr)| {
            let sig = wav.get_signal(*sr).expect("signal not loaded");
            (n.clone(), sig)
        })
        .collect();

    let read_signal = |name: &str, ci: usize| -> bool {
        if let Some(sig) = signal_sigs.get(name) {
            let (ti, _) = win[ci];
            read_bit(sig, ti as u32)
        } else {
            false
        }
    };
    let read_value = |name: &str, ci: usize| -> Option<u64> {
        if let Some(sig) = signal_sigs.get(name) {
            let (ti, _) = win[ci];
            read_u64(sig, ti as u32)
        } else {
            None
        }
    };

    Ok(crate::cmd::property::expr::eval::eval_temporal(
        &ast,
        win,
        &read_signal,
        &read_value,
    ))
}

/// Map tick matches back to window-local cycle indices.
pub(crate) fn matches_to_cycles(
    matches: &[SerdeRange<u64>],
    win: &[(usize, u64)],
) -> Vec<SerdeRange<u64>> {
    let tick_to_cycle: BTreeMap<u64, usize> = win
        .iter()
        .enumerate()
        .map(|(i, &(_ti, t))| (t, i))
        .collect();
    matches
        .iter()
        .map(|r| {
            let s = tick_to_cycle.get(r.0.start()).copied().unwrap_or(0) as u64;
            let e = tick_to_cycle.get(r.0.end()).copied().unwrap_or(0) as u64;
            SerdeRange(s..=e)
        })
        .collect()
}

impl crate::Pulse {
    pub(crate) fn plot(
        &mut self,
        scope_path: &str,
        signal_names: &[String],
        cycles: Option<SerdeRange<u64>>,
        marks: &[String],
        out: Option<&Path>,
    ) -> Result<(), WaveError> {
        let prepared = prepare_wave(&mut self.wav, scope_path, signal_names)?;
        let win = slice_cycles(&prepared.cycles, cycles)?;

        let signals: Vec<&wellen::Signal> = signal_names
            .iter()
            .map(|name| {
                let sr = prepared.name_to_sref[name];
                self.wav.get_signal(sr).expect("signal not loaded")
            })
            .collect();

        // Sample every requested signal per cycle.
        let samples: Vec<Vec<Val>> = signals
            .iter()
            .map(|sig| win.iter().map(|&(ti, _)| sample(sig, ti as u32)).collect())
            .collect();

        // Mark expressions → cycle-index ranges (same pipeline as property eval).
        let mut mark_data: Vec<(String, Vec<SerdeRange<u64>>)> = Vec::new();
        for expr in marks {
            let matches = eval_matches(&self.wav, &win, &prepared.name_to_sref, expr)?;
            mark_data.push((expr.clone(), matches_to_cycles(&matches, &win)));
        }

        let data = PlotData {
            title: scope_path.to_string(),
            signals: signal_names.to_vec(),
            cycles: win.clone(),
            samples,
            marks: mark_data.iter().map(|(_, m)| m.clone()).collect(),
            mark_names: mark_data.iter().map(|(n, _)| n.clone()).collect(),
        };
        let svg = self::svg::render_svg(&data);

        match out {
            Some(path) => {
                std::fs::write(path, svg).map_err(|e| {
                    WaveError::Io(std::io::Error::new(
                        e.kind(),
                        format!("{}: {e}", path.display()),
                    ))
                })?;
                println!(
                    "wrote {} ({} cycles, {} signals, {}×{} px)",
                    path.display(),
                    win.len(),
                    signal_names.len(),
                    // svg viewBox width/height: recompute cheaply from layout.
                    layout_size(win.len(), signal_names.len()).0,
                    layout_size(win.len(), signal_names.len()).1,
                );
            }
            None => {
                let stdout = std::io::stdout();
                let mut lock = stdout.lock();
                lock.write_all(svg.as_bytes())?;
            }
        }
        Ok(())
    }
}

fn layout_size(n_cycles: usize, n_signals: usize) -> (u32, u32) {
    let w = (svg::LEFT + n_cycles.max(1) as f64 * svg::PX_PER_CYCLE) as u32;
    let plot_top = 18.0 + 16.0; // marks header always reserved (cheap)
    let h = (plot_top + n_signals as f64 * svg::ROW_H + svg::AXIS_H) as u32;
    (w, h)
}
