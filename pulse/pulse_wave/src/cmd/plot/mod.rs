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
    // Scope + signal resolution and load (same entry as `value`).
    let name_to_sref = crate::cmd::hierarchy::resolve_and_load(wav, scope_path, signal_names)?;

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

/// Classify a signal sample into a plot/diff value (thin wrapper over the
/// shared [`crate::read::classify`]).
pub(crate) fn sample(sig: &wellen::Signal, tt_idx: u32) -> Val {
    use crate::read::SignalValue;
    match crate::read::classify(sig, tt_idx) {
        SignalValue::Bit(true) => Val::High,
        SignalValue::Bit(false) => Val::Low,
        SignalValue::Vector { value, .. } => Val::Vector(value),
        SignalValue::X(_) | SignalValue::Missing => Val::Unknown,
    }
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

        // Mark expressions → cycle-index ranges (shared engine pipeline).
        let mut mark_data: Vec<(String, Vec<SerdeRange<u64>>)> = Vec::new();
        for expr in marks {
            let matches = crate::cmd::engine::eval_matches(
                &self.wav,
                &win,
                &prepared.name_to_sref,
                expr,
                &BTreeMap::new(),
                "",
            )?;
            mark_data.push((
                expr.clone(),
                crate::cycle::local_cycle_ranges(&win, &matches),
            ));
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
