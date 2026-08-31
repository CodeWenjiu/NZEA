use std::collections::BTreeMap;

use wellen::SignalRef;

use crate::WaveError;

impl crate::Pulse {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn property(
        &mut self,
        scope_path: Option<&str>,
        on: &str,
        evals: &[String],
        event_tokens: &[String],
        cycles: Option<crate::SerdeRange<u64>>,
        max: Option<usize>,
        count: bool,
    ) -> Result<(), WaveError> {
        if evals.is_empty() {
            return Err(WaveError::Parse("at least one --eval is required".into()));
        }
        if count && max.is_some() {
            return Err(WaveError::Parse(
                "--count and --max are mutually exclusive".into(),
            ));
        }

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

        // ── Parse and normalize every eval expression ───────────
        let mut asts: Vec<(String, super::expr::ast::Expr)> = Vec::new();
        for e in evals {
            let ast = super::expr::parser::parse(e)?;
            let ast = super::event::normalize(&ast, "", &event_defs);
            super::expr::stdlib::check_functions(&ast).map_err(WaveError::Parse)?;
            let ast = super::expr::stdlib::desugar(&ast);
            asts.push((e.clone(), ast));
        }

        // ── Collect referenced signals per scope (all evals) ────
        let mut refs: Vec<(Option<String>, String)> = Vec::new();
        for (_, ast) in &asts {
            super::event::collect_signal_refs(ast, &mut refs);
        }
        refs.sort();
        refs.dedup();

        // Resolve signals: main scope + each event set's scope
        let mut name_to_sref: BTreeMap<String, SignalRef> = BTreeMap::new();
        {
            let h = self.wav.hierarchy();

            // Main scope signals
            let main_names: Vec<String> = refs
                .iter()
                .filter(|(ns, _)| ns.is_none())
                .map(|(_, n)| n.clone())
                .collect();
            if !main_names.is_empty() {
                let map = super::super::hierarchy::resolve_signals(h, &scope_path, &main_names)?;
                name_to_sref.extend(map);
            }

            // Per event-set signals, qualified with the set name
            for (ns, names) in group_by_namespace(&refs) {
                let scope = event_scopes
                    .get(&ns)
                    .ok_or_else(|| WaveError::Parse(format!("undefined event set '{ns}'")))?;
                let map = super::super::hierarchy::resolve_signals(h, scope, &names)?;
                for (local, sr) in map {
                    name_to_sref.insert(format!("{ns}.{local}"), sr);
                }
            }

            // Clock signal (main scope)
            let clock_map = super::super::hierarchy::resolve_signals(
                h,
                &scope_path,
                &[clock_name.to_string()],
            )?;
            name_to_sref.extend(clock_map);
        }

        // ── Load signal data ────────────────────────────────────
        let srefs: Vec<SignalRef> = name_to_sref.values().copied().collect();
        self.wav.load_signals(&srefs);

        let clock_sig = self
            .wav
            .get_signal(name_to_sref[clock_name])
            .expect("clock signal not loaded");

        let signal_sigs: BTreeMap<String, &wellen::Signal> = name_to_sref
            .iter()
            .map(|(n, sr)| {
                let sig = self.wav.get_signal(*sr).expect("signal not loaded");
                (n.clone(), sig)
            })
            .collect();

        // ── Build cycle list from clock posedges ────────────────
        let tt = self.wav.time_table();
        let mut all_cycles: Vec<(usize, u64)> = Vec::new();
        let mut prev_clk = false;
        for (ti, &t) in tt.iter().enumerate() {
            let clk_val = super::signals::read_bit(clock_sig, ti as u32);
            let is_posedge = !prev_clk && clk_val;
            if is_posedge {
                all_cycles.push((ti, t));
            }
            prev_clk = clk_val;
        }

        // ── Slice cycles per --cycles ───────────────────────────
        let (from, to) = match cycles {
            Some(r) => {
                // `--cycles ~N` → last N cycles.
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
        if from >= all_cycles.len() {
            // No cycles in the requested window: empty matches, rendered in
            // the requested shape (single/multi × ranges/count).
            let columns: Vec<(String, Vec<crate::SerdeRange<u64>>)> = asts
                .iter()
                .map(|(name, _)| (name.clone(), Vec::new()))
                .collect();
            let out = super::output::render(
                &columns,
                count,
                crate::SerdeRange(from..=from),
                all_cycles.len(),
                max,
            );
            self.emit(&out);
            return Ok(());
        }
        let to = to.min(all_cycles.len());
        if from >= to {
            return Err(WaveError::Parse(format!(
                "--cycles {from}-{to}: from >= to"
            )));
        }
        let cycles = &all_cycles[from..to];

        // tick → global cycle index (from + local index). The evaluator works
        // in ticks (temporal pairing needs absolute time); we map its output
        // back to cycle indices so that `from`/`to`/`total_cycles`/`matches`
        // are all in the same unit (cycle numbers, matching the `--cycles`
        // window).
        let tick_to_cycle: std::collections::BTreeMap<u64, usize> = cycles
            .iter()
            .enumerate()
            .map(|(i, &(_tt_idx, t))| (t, from + i))
            .collect();
        let map_tick_range = |r: &crate::SerdeRange<u64>| -> crate::SerdeRange<u64> {
            let s = tick_to_cycle
                .get(r.0.start())
                .copied()
                .unwrap_or(*r.0.start() as usize) as u64;
            let e = tick_to_cycle
                .get(r.0.end())
                .copied()
                .unwrap_or(*r.0.end() as usize) as u64;
            crate::SerdeRange(s..=e)
        };

        // ── Evaluate all expressions ────────────────────────────
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

        let columns: Vec<(String, Vec<crate::SerdeRange<u64>>)> = asts
            .iter()
            .map(|(name, ast)| {
                let mut matches =
                    super::expr::eval::eval_temporal(ast, cycles, &read_signal, &read_value);
                for r in matches.iter_mut() {
                    *r = map_tick_range(r);
                }
                if let Some(n) = max {
                    matches.truncate(n);
                }
                (name.clone(), matches)
            })
            .collect();

        // ── Output ──────────────────────────────────────────────
        let out = super::output::render(
            &columns,
            count,
            crate::SerdeRange(from..=to),
            all_cycles.len(),
            max,
        );
        self.emit(&out);

        Ok(())
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
