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
        // match, through the same engine pipeline as --eval. A comma is never
        // part of an expression, so reject it here: the expression parser's
        // "unexpected trailing input" would not hint at `--signals`-style
        // comma splitting.
        enum SampleSpec {
            Signal(String),
            Expr(super::expr::ast::Expr),
        }
        let mut sample_specs: Vec<(String, SampleSpec)> = Vec::new();
        for s in samples {
            if s.contains(',') {
                return Err(WaveError::Parse(format!(
                    "--sample takes one name per occurrence, but '{s}' contains a comma; \
                     pass --sample once per signal (--sample does not split on commas, \
                     unlike --signals)"
                )));
            }
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

// ── tests ─────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    /// An ILA-style flat export: one scope, every variable named by its path
    /// inside it (`ddr3/state`), and the ids Vivado's exporter actually writes —
    /// including ids that start with VCD's own structural characters (`#0`,
    /// `$0`), ids that differ only in case (`A/` vs `a/`), a punctuation id
    /// (`;/`), a quote inside an id (`V"`), and a one-character id (`&`).
    ///
    /// This is the file class the board's captures are: a reading that confuses
    /// a name with its neighbour, or an id with a timestamp, produces a silently
    /// wrong value — the one failure mode a debugging tool cannot afford.
    const FLAT_EXPORT: &str = r#"$date
        2026-Oct-17 00:00:00
$end
$version
        pulse fixture: an ILA-style flat export
$end
$timescale
        1ps
$end
$scope module dut $end
$var reg 1 #0 soak/done $end
$var reg 6 ;/ soak/step $end
$var reg 3 8/ soak/phase $end
$var reg 32 A/ ddr3/issued $end
$var reg 32 a/ ddr3/errors $end
$var reg 32 $0 ddr3/firstBadAddr $end
$var reg 6 & ddr3/state $end
$var reg 1 + ddr3/respValid $end
$var reg 64 V" ddr3/captured $end
$var reg 1 b3 _TRIGGER $end
$enddefinitions $end
#0
$dumpvars
0#0
b1010 ;/
b000 8/
b0 A/
b0 a/
b0 $0
b001 &
0+
b0 V"
0b3
$end
#3
b1100 ;/
b001 8/
b10 A/
b011 &
b10101111 V"
1+
#4
b11 A/
#5
b011 8/
#6
b1101 ;/
1#0
b100 A/
b11110000 V"
0+
1b3
#7
b101 A/
"#;

    /// Write the fixture into the test temp directory and load it.
    fn load_fixture(name: &str) -> (wellen::simple::Waveform, std::path::PathBuf) {
        let path = std::env::temp_dir().join(format!("pulse-test-{name}.vcd"));
        std::fs::write(&path, FLAT_EXPORT).unwrap();
        let wav = crate::load_waveform(&path).expect("the fixture must load");
        (wav, path)
    }

    /// The sample a command reads out of a signal at one time-table index — the
    /// same accessor `value`, `property --sample` and the report paths use.
    fn cell(wav: &wellen::simple::Waveform, sr: wellen::SignalRef, tt_idx: u32) -> u64 {
        let sig = wav.get_signal(sr).expect("signal not loaded");
        match crate::read::classify(sig, tt_idx) {
            crate::read::SignalValue::Bit(b) => u64::from(b),
            crate::read::SignalValue::Vector { value, .. } => value,
            other => panic!("tick {tt_idx}: unexpected sample {other:?}"),
        }
    }

    /// Every tick a match list covers, in order (the engine emits one range per
    /// matching cycle; a query's answer is the set of those cycles).
    fn matched_ticks(m: &[crate::SerdeRange<u64>]) -> Vec<u64> {
        m.iter().flat_map(|r| r.0.clone()).collect()
    }

    #[test]
    fn a_flat_export_reads_back_exactly() {
        let (mut wav, path) = load_fixture("flat-export-reads");
        let names: Vec<String> = [
            "soak/done",
            "soak/step",
            "soak/phase",
            "ddr3/issued",
            "ddr3/errors",
            "ddr3/firstBadAddr",
            "ddr3/state",
            "ddr3/respValid",
            "ddr3/captured",
            "_TRIGGER",
        ]
        .map(String::from)
        .to_vec();
        let map = crate::cmd::hierarchy::resolve_and_load(&mut wav, "dut", &names).unwrap();

        // The fixture declares markers at 0, 3, 4, 5, 6 and 7 only, so those
        // are the export's samples: one per marker, not one per integer tick.
        assert_eq!(wav.time_table(), &[0, 3, 4, 5, 6, 7]);

        // One row per marker: the two never-changing signals, the case-only
        // pair, the `#`/`$`-leading ids and the one that changes last all have
        // to land in their own columns.
        let expect: [(&str, [u64; 6]); 10] = [
            ("soak/done", [0, 0, 0, 0, 1, 1]),
            ("soak/step", [10, 12, 12, 12, 13, 13]),
            ("soak/phase", [0, 1, 1, 3, 3, 3]),
            ("ddr3/issued", [0, 2, 3, 3, 4, 5]),
            ("ddr3/errors", [0; 6]),
            ("ddr3/firstBadAddr", [0; 6]),
            ("ddr3/state", [1, 3, 3, 3, 3, 3]),
            ("ddr3/respValid", [0, 1, 1, 1, 0, 0]),
            ("ddr3/captured", [0, 0xaf, 0xaf, 0xaf, 0xf0, 0xf0]),
            ("_TRIGGER", [0, 0, 0, 0, 1, 1]),
        ];
        for (name, want) in expect {
            let sr = map[name];
            for (i, w) in want.iter().enumerate() {
                assert_eq!(cell(&wav, sr, i as u32), *w, "{name} at tick {i}");
            }
        }
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_flat_export_evaluates_by_its_path_names() {
        let (mut wav, path) = load_fixture("flat-export-eval");
        let names: Vec<String> = ["ddr3/state", "soak/done", "_TRIGGER"]
            .map(String::from)
            .to_vec();
        let map = crate::cmd::hierarchy::resolve_and_load(&mut wav, "dut", &names).unwrap();
        // No clock signal in a flat export: every time-table entry is a cycle.
        let cycles = crate::cycle::build_cycles(&mut wav, "dut", Some("clock"), false).unwrap();
        let none = BTreeMap::new();

        let m = crate::cmd::engine::eval_matches(
            &wav,
            &cycles,
            &map,
            "ddr3/state == 3 && soak/done == 0",
            &none,
            "",
        )
        .unwrap();
        // The engine emits one range per matching cycle; the assertion is about
        // *which* cycles, so it reads the union of the ranges.
        assert_eq!(matched_ticks(&m), [3, 4, 5], "{m:?}");

        let m = crate::cmd::engine::eval_matches(
            &wav,
            &cycles,
            &map,
            "soak/done == 1 || _TRIGGER == 1",
            &none,
            "",
        )
        .unwrap();
        assert_eq!(matched_ticks(&m), [6, 7], "{m:?}");

        // The variable *is* the path: the leaf alone is not a name in a flat export.
        let h = wav.hierarchy();
        assert!(crate::cmd::hierarchy::resolve_signals(h, "dut", &["state".into()]).is_err());
        let _ = std::fs::remove_file(&path);
    }

    /// A long flat export with the same id shapes: 1024 markers, every signal
    /// sparse except one, values that walk. The board's captures are this shape
    /// (an ILA's 1024-sample window), and a sample index resolved one step off
    /// reads here as a shifted column (an observed one-off mis-read on a real
    /// capture is what this pins against).
    #[test]
    fn a_long_flat_export_reads_back_exactly() {
        let ids = ["#0", ";/", "A/", "a/", "V\"", "b3"];
        let names = [
            "soak/done",
            "soak/step",
            "ddr3/issued",
            "ddr3/errors",
            "ddr3/captured",
            "_TRIGGER",
        ];
        let widths = [1, 6, 32, 32, 64, 1];
        let row = |t: u64| -> [u64; 6] {
            [
                u64::from(t >= 1000),
                10 + t / 64, // a 6-bit signal, changing every 64th sample
                t,
                0,
                t * 3,
                u64::from(t == 512),
            ]
        };
        let line = |i: usize, v: u64| -> String {
            if widths[i] == 1 {
                format!("{v}{}", ids[i])
            } else {
                format!("b{v:b} {}", ids[i])
            }
        };

        let mut text = String::from("$timescale\n        1ps\n$end\n$scope module dut $end\n");
        for (i, name) in names.iter().enumerate() {
            text.push_str(&format!("$var reg {} {} {name} $end\n", widths[i], ids[i]));
        }
        text.push_str("$enddefinitions $end\n");
        let mut prev: Option<[u64; 6]> = None;
        for t in 0..1024u64 {
            let now = row(t);
            text.push_str(&format!("#{t}\n"));
            if prev.is_none() {
                text.push_str("$dumpvars\n");
            }
            for i in 0..6 {
                if prev.is_none_or(|p| p[i] != now[i]) {
                    text.push_str(&line(i, now[i]));
                    text.push('\n');
                }
            }
            if prev.is_none() {
                text.push_str("$end\n");
            }
            prev = Some(now);
        }

        let path = std::env::temp_dir().join("pulse-test-flat-export-long.vcd");
        std::fs::write(&path, text).unwrap();
        let mut wav = crate::load_waveform(&path).expect("the long fixture must load");
        let names: Vec<String> = names.map(String::from).to_vec();
        let map = crate::cmd::hierarchy::resolve_and_load(&mut wav, "dut", &names).unwrap();
        assert_eq!(wav.time_table().len(), 1024, "one marker per sample");
        for t in 0..1024u64 {
            let want = row(t);
            for (i, name) in names.iter().enumerate() {
                assert_eq!(
                    cell(&wav, map[name], t as u32),
                    want[i],
                    "{name} at tick {t}"
                );
            }
        }
        let _ = std::fs::remove_file(&path);
    }
}
