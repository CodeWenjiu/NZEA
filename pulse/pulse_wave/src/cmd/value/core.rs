use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;
use crate::time_spec::{AtSpec, ns_to_ticks};

impl crate::Pulse {
    pub(crate) fn value(
        &mut self,
        scope_path: &str,
        at_tokens: &[String],
        changes: bool,
        signal_names: &[String],
        radix: &str,
    ) -> Result<(), WaveError> {
        let radix = crate::radix::Radix::from_str(radix)?;
        let last_tick = self.wav.time_table().last().copied().unwrap_or(0);

        // ── Parse --at tokens ────────────────────────────────────
        // Commas split plain ranges; match(EXPR) is never split (its
        // expression may contain commas).
        let specs = parse_at_tokens(at_tokens)?;

        // match() expressions may reference signals beyond --signals; parse
        // them now, collect those names and resolve/load everything together.
        let mut all_names: Vec<String> = signal_names.to_vec();
        let mut match_exprs: Vec<String> = Vec::new();
        for spec in &specs {
            if let AtSpec::Match(expr) = spec {
                let ast = crate::cmd::property::expr::parser::parse(expr)?;
                let mut refs: Vec<(Option<String>, String)> = Vec::new();
                crate::cmd::property::event::collect_signal_refs(&ast, &mut refs);
                for (ns, name) in refs {
                    if ns.is_some() {
                        return Err(WaveError::Parse(format!(
                            "match() cannot reference event sets (value has no --event): '{name}'"
                        )));
                    }
                    if !all_names.iter().any(|n| n == &name) {
                        all_names.push(name.clone());
                    }
                }
                match_exprs.push(expr.clone());
            }
        }

        let name_to_sref = {
            let h = self.wav.hierarchy();
            super::super::hierarchy::resolve_signals(h, scope_path, &all_names)?
        };
        let srefs: Vec<SignalRef> = name_to_sref.values().copied().collect();
        self.wav.load_signals(&srefs);

        // Cycle list, once: cycle-domain --at specs and match() evaluation
        // both consume it (posedges of `clock`, or every tt entry when the
        // scope has no clock — the same TimeBase every command shares).
        let all_cycles =
            crate::cycle::build_cycles(&mut self.wav, scope_path, Some("clock"), false)?;

        // ── Resolve --at specs to concrete sample ticks ─────────
        let times: Vec<u64> = if changes {
            // --changes scans the whole trace; --at is rejected by clap.
            Vec::new()
        } else {
            let timescale = self.wav.hierarchy().timescale();
            let (scale_f, scale_u) = timescale
                .map(|ts| (ts.factor, ts.unit))
                .unwrap_or((1, wellen::TimescaleUnit::PicoSeconds));
            let mut out = Vec::new();
            for spec in &specs {
                match spec {
                    AtSpec::Ticks(r) => out.extend(resolve_ticks(r, last_tick)),
                    AtSpec::Cycles(r) => {
                        // Nth posedge-clock cycle → its tick.
                        let i = *r.0.start() as usize;
                        if i >= all_cycles.len() {
                            return Err(WaveError::Parse(format!(
                                "--at {i}c: only {} cycles in this dump",
                                all_cycles.len()
                            )));
                        }
                        out.push(all_cycles[i].1);
                    }
                    AtSpec::AbsNs(r) => {
                        out.push(ns_to_ticks(*r.0.start(), u64::from(scale_f), scale_u))
                    }
                    AtSpec::Match(expr) => {
                        // Reuse the shared event engine (same evaluator as
                        // property/plot/diff); every match's start tick is a
                        // sample point.
                        let matches = crate::cmd::plot::eval_matches(
                            &self.wav,
                            &all_cycles,
                            &name_to_sref,
                            expr,
                        )?;
                        for m in &matches {
                            out.push(*m.0.start());
                        }
                    }
                }
            }
            out
        };

        let signals: Vec<(String, &wellen::Signal)> = signal_names
            .iter()
            .map(|name| {
                // name_to_sref is a BTreeMap (alphabetical); iterate in the
                // requested order so that values align with signal_names in
                // the output (JSON keys and text columns).
                let sr = name_to_sref[name];
                let sig = self.wav.get_signal(sr).expect("signal not loaded");
                (name.clone(), sig)
            })
            .collect();

        let tt = self.wav.time_table();
        let samples: Vec<super::output::Sample> = if changes {
            // One row per tick where ANY requested signal value differs from the
            // previous tick (the first tick always appears: initial state).
            // Multi-signal rows stay aligned so change sets can be diffed
            // across signals (e.g. two nets with identical toggle history).
            let mut prev: Vec<Option<super::output::Val>> = vec![None; signals.len()];
            let mut rows = Vec::new();
            for (i, &t) in tt.iter().enumerate() {
                let values: Vec<super::output::Val> = signals
                    .iter()
                    .map(|(_, sig)| sig_val(sig, i as u32, radix))
                    .collect();
                if prev.iter().zip(&values).any(|(p, v)| p.as_ref() != Some(v)) {
                    rows.push(super::output::Sample {
                        time: t,
                        values: values.clone(),
                    });
                }
                prev = values.into_iter().map(Some).collect();
            }
            rows
        } else {
            times
                .iter()
                .map(|&t| {
                    let tt_idx: u32 = tt
                        .binary_search(&t)
                        .unwrap_or_else(|i| i.saturating_sub(1))
                        .try_into()
                        .unwrap_or(0);
                    let values: Vec<super::output::Val> = signals
                        .iter()
                        .map(|(_, sig)| sig_val(sig, tt_idx, radix))
                        .collect();
                    super::output::Sample { time: t, values }
                })
                .collect()
        };

        self.emit(&super::output::ValueOut {
            scope: scope_path.to_string(),
            signal_names: signal_names.to_vec(),
            samples,
        });
        Ok(())
    }
}

/// Split `--at` tokens into specs. Bare comma lists expand; `match(...)`
/// never splits.
fn parse_at_tokens(tokens: &[String]) -> Result<Vec<AtSpec>, WaveError> {
    let mut out = Vec::new();
    for t in tokens {
        let t = t.trim();
        if t.starts_with("match(") {
            out.push(AtSpec::from_str(t).map_err(WaveError::Parse)?);
        } else {
            for piece in t.split(',') {
                let piece = piece.trim();
                if !piece.is_empty() {
                    out.push(AtSpec::from_str(piece).map_err(WaveError::Parse)?);
                }
            }
        }
    }
    Ok(out)
}

/// Expand a bare tick range: `~N` tail sentinel resolved against the last
/// tick, open-ended ranges clamped to it.
fn resolve_ticks(r: &SerdeRange<u64>, last_tick: u64) -> Vec<u64> {
    if let Some(n) = r.tail_n() {
        let start = last_tick.saturating_sub(n - 1);
        return (start..=last_tick).collect();
    }
    let end = (*r.0.end()).min(last_tick);
    SerdeRange(*r.0.start()..=end).resolve()
}

fn sig_val(sig: &wellen::Signal, tt_idx: u32, radix: crate::radix::Radix) -> super::output::Val {
    let val = sig.get_offset(tt_idx).map(|off| sig.get_value_at(&off, 0));
    match val {
        Some(v) => {
            if v.width() == Some(1) {
                let s = format!("{v}");
                super::output::Val::Bit(s.starts_with('1'))
            } else {
                // Multi-bit: render in the requested radix; fall back to the
                // raw binary string for X/Z or values wider than u64.
                let width = v.width().unwrap_or(32) as usize;
                match u64::try_from(v) {
                    Ok(n) => super::output::Val::Hex(radix.render(width, n)),
                    Err(_) => super::output::Val::Hex(format!("{v}")),
                }
            }
        }
        None => super::output::Val::Unknown,
    }
}
