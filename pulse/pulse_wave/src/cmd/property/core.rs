use std::collections::BTreeMap;

use wellen::SignalRef;

use crate::WaveError;

impl crate::Pulse {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn property(
        &mut self,
        scope_path: Option<&str>,
        on: Option<&str>,
        evals: &[String],
        event_tokens: &[String],
        cycles: Option<crate::SerdeRange<u64>>,
        max: Option<usize>,
        count: bool,
        samples: &[String],
        radix: &str,
    ) -> Result<(), WaveError> {
        if evals.is_empty() {
            return Err(WaveError::Parse("at least one --eval is required".into()));
        }
        if count && max.is_some() {
            return Err(WaveError::Parse(
                "--count and --max are mutually exclusive".into(),
            ));
        }
        if !samples.is_empty() && count {
            return Err(WaveError::Parse(
                "--count and --sample are mutually exclusive".into(),
            ));
        }
        if !samples.is_empty() && evals.len() > 1 {
            return Err(WaveError::Parse(
                "--sample requires exactly one --eval (multi-column sampling is ambiguous)".into(),
            ));
        }
        let radix = crate::radix::Radix::from_str(radix)?;

        // `--on` defaults to "posedge clock"; when the user did not pass it
        // explicitly and the scope has no clock signal, fall back to treating
        // every time-table entry as one cycle. This is the natural semantic for
        // ILA VCD exports (timestamps = sample indices, no clock in the dump).
        let explicit_on = on.is_some();
        let on = on.unwrap_or("posedge clock");
        let clock_name = super::clock::parse_clock(on)?;
        let events =
            crate::command::EventDef::from_tokens(event_tokens).map_err(WaveError::Parse)?;

        // Resolve default scope to the top-level module
        let scope_path = match scope_path {
            Some(p) => p.to_string(),
            None => super::super::hierarchy::top_scope(self.wav.hierarchy())?,
        };

        // ── Load event definitions ──────────────────────────────
        let mut event_defs: BTreeMap<String, super::expr::ast::Expr> = BTreeMap::new();
        let mut event_scopes: BTreeMap<String, String> = BTreeMap::new();
        for def in events {
            let defs = super::event::load(&def.source)?;
            for (local, expr) in defs {
                event_defs.insert(format!("{}.{}", def.name, local), expr);
            }
            event_scopes.insert(def.name.clone(), def.scope.clone());
        }

        // ── Compile eval expressions (shared engine pipeline) ───
        let mut asts: Vec<(String, super::expr::ast::Expr)> = Vec::new();
        for e in evals {
            let ast = crate::cmd::engine::compile(e, &event_defs, "")?;
            asts.push((e.clone(), ast));
        }

        // `--sample` entries: a bare signal name samples that signal; anything
        // else is a value expression (comparison/logic) evaluated to 0/1 per
        // match, through the same engine pipeline as --eval.
        enum SampleSpec {
            Signal(String),
            Expr(super::expr::ast::Expr),
        }
        let mut sample_specs: Vec<(String, SampleSpec)> = Vec::new();
        for s in samples {
            let ast = crate::cmd::engine::compile(s, &event_defs, "")?;
            match &ast {
                super::expr::ast::Expr::Signal(name) => {
                    sample_specs.push((s.clone(), SampleSpec::Signal(name.clone())))
                }
                _ => sample_specs.push((s.clone(), SampleSpec::Expr(ast))),
            }
        }

        // ── Collect referenced signals per scope (all evals) ────
        let mut refs: Vec<(Option<String>, String)> = Vec::new();
        for (_, ast) in &asts {
            super::event::collect_signal_refs(ast, &mut refs);
        }
        // --sample signals belong to the main scope.
        for (_, spec) in &sample_specs {
            match spec {
                SampleSpec::Signal(name) => refs.push((None, name.clone())),
                SampleSpec::Expr(ast) => super::event::collect_signal_refs(ast, &mut refs),
            }
        }
        refs.sort();
        refs.dedup();

        // Resolve signals: main scope (resolved + loaded in one step) plus
        // each event set's scope.
        let mut name_to_sref: BTreeMap<String, SignalRef> = BTreeMap::new();
        let main_names: Vec<String> = refs
            .iter()
            .filter(|(ns, _)| ns.is_none())
            .map(|(_, n)| n.clone())
            .collect();
        if !main_names.is_empty() {
            let map =
                crate::cmd::hierarchy::resolve_and_load(&mut self.wav, &scope_path, &main_names)?;
            name_to_sref.extend(map);
        }
        for (ns, names) in group_by_namespace(&refs) {
            let scope = event_scopes
                .get(&ns)
                .ok_or_else(|| WaveError::Parse(format!("undefined event set '{ns}'")))?;
            let map = {
                let h = self.wav.hierarchy();
                super::super::hierarchy::resolve_signals(h, scope, &names)?
            };
            for (local, sr) in map {
                name_to_sref.insert(format!("{ns}.{local}"), sr);
            }
        }

        // ── Build cycle list from clock posedges ────────────────
        // Without a clock, every time-table entry is one cycle (e.g. ILA
        // exports where timestamps are sample indices).
        let all_cycles =
            crate::cycle::build_cycles(&mut self.wav, &scope_path, Some(clock_name), explicit_on)?;

        // ── Load signal data (event-set signals; main scope already loaded) ──
        let srefs: Vec<SignalRef> = name_to_sref.values().copied().collect();
        self.wav.load_signals(&srefs);

        let signal_sigs: BTreeMap<String, &wellen::Signal> = name_to_sref
            .iter()
            .map(|(n, sr)| {
                let sig = self.wav.get_signal(*sr).expect("signal not loaded");
                (n.clone(), sig)
            })
            .collect();

        // ── Slice cycles per --cycles ───────────────────────────
        let (from, to) = crate::cycle::window_bounds(all_cycles.len(), cycles.as_ref());
        if from >= all_cycles.len() || from >= to {
            return Err(WaveError::Parse(format!(
                "--cycles {from}-{to}: empty window (trace has {} cycles)",
                all_cycles.len()
            )));
        }
        let cycles = &all_cycles[from..to];

        // ── Evaluate all expressions ────────────────────────────
        // The evaluator works in ticks (temporal pairing needs absolute
        // time); matches are mapped back to global {cycle, tick} pairs by the
        // shared TimeBase so consumers never convert.
        let read_signal = |name: &str, cycle_idx: usize| -> bool {
            if let Some(sig) = signal_sigs.get(name) {
                let (tt_idx, _) = cycles[cycle_idx];
                super::signals::read_bit(sig, tt_idx as u32)
            } else {
                false
            }
        };
        let read_value = |name: &str, cycle_idx: usize| -> Option<u64> {
            if let Some(sig) = signal_sigs.get(name) {
                let (tt_idx, _) = cycles[cycle_idx];
                super::signals::read_u64(sig, tt_idx as u32)
            } else {
                None
            }
        };

        let columns: Vec<(String, Vec<crate::SerdeRange<crate::cycle::CycleTick>>)> = asts
            .iter()
            .map(|(name, ast)| {
                let raw = super::expr::eval::eval_temporal(ast, cycles, &read_signal, &read_value);
                let mut matches: Vec<crate::SerdeRange<crate::cycle::CycleTick>> = raw
                    .iter()
                    .map(|r| crate::cycle::map_match(from, cycles, r))
                    .collect();
                if let Some(n) = max {
                    matches.truncate(n);
                }
                (name.clone(), matches)
            })
            .collect();

        // ── --sample: one row per match (single --eval, enforced above) ──
        // Signal samples render width-aware; expression samples evaluate
        // through the same value engine (comparisons/logic → 0/1, X → "?").
        let mut sample_rows: Vec<(
            crate::SerdeRange<crate::cycle::CycleTick>,
            super::output::SampleOut,
        )> = Vec::new();
        if !sample_specs.is_empty() {
            for r in &columns[0].1 {
                let ct = *r.0.start();
                let ci = ct.cycle as usize - from;
                let (tt_idx, tick) = cycles[ci];
                let values: Vec<(String, String)> = sample_specs
                    .iter()
                    .map(|(label, spec)| match spec {
                        SampleSpec::Signal(name) => {
                            let sig = signal_sigs
                                .get(name)
                                .unwrap_or_else(|| panic!("sample signal '{name}' not resolved"));
                            (label.clone(), sample_value(sig, tt_idx as u32, radix))
                        }
                        SampleSpec::Expr(ast) => {
                            let v = super::expr::eval::eval_value(
                                ast,
                                ci,
                                cycles,
                                &read_signal,
                                &read_value,
                            );
                            (
                                label.clone(),
                                v.map(|b| if b == 0 { "0" } else { "1" })
                                    .unwrap_or("?")
                                    .into(),
                            )
                        }
                    })
                    .collect();
                sample_rows.push((
                    r.clone(),
                    super::output::SampleOut {
                        cycle: ct.cycle,
                        tick,
                        values,
                    },
                ));
            }
        }

        // ── Output ──────────────────────────────────────────────
        let out = super::output::render(
            &columns,
            count,
            crate::SerdeRange(from..=to),
            all_cycles.len(),
            max,
            &sample_rows,
        );
        self.emit(&out);

        Ok(())
    }
}

/// Render one --sample value from the shared classification: 1-bit signals as
/// 0/1/?, vectors in the requested radix, X/Z/missing as "?".
fn sample_value(sig: &wellen::Signal, tt_idx: u32, radix: crate::radix::Radix) -> String {
    match crate::read::classify(sig, tt_idx) {
        crate::read::SignalValue::Bit(true) => "1".into(),
        crate::read::SignalValue::Bit(false) => "0".into(),
        crate::read::SignalValue::Vector { value, width } => radix.render(width, value),
        crate::read::SignalValue::X(_) | crate::read::SignalValue::Missing => "?".into(),
    }
}

/// Group signal references by namespace: `(ns, [signal names])`.
fn group_by_namespace(refs: &[(Option<String>, String)]) -> Vec<(String, Vec<String>)> {
    let mut groups: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for (ns, name) in refs {
        if let Some(ns) = ns {
            groups.entry(ns.clone()).or_default().push(name.clone());
        }
    }
    groups.into_iter().collect()
}
