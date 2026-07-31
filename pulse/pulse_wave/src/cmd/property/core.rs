use std::collections::BTreeMap;

use wellen::{Item, SignalRef};

use crate::WaveError;

impl crate::Pulse {
    pub(crate) fn property(
        &mut self,
        scope_path: &str,
        on: &str,
        eval: &str,
        cycles: Option<&str>,
    ) -> Result<(), WaveError> {
        let clock_name = super::clock::parse_clock(on)?;
        let ast = super::expr::parser::parse(eval)?;
        let signal_names = super::signals::collect_signals(&ast);

        let target = {
            let h = self.wav.hierarchy();
            match crate::find_scope(h, &scope_path) {
                Some(sr) => sr,
                None => {
                    return Err(WaveError::Parse(format!("scope '{scope_path}' not found")));
                }
            }
        };

        let name_to_sref = {
            let h = self.wav.hierarchy();
            let all_names: Vec<&str> = signal_names
                .iter()
                .map(|s| s.as_str())
                .chain(std::iter::once(clock_name))
                .collect();

            let mut map: BTreeMap<String, SignalRef> = BTreeMap::new();
            for r in h[target].items(h) {
                let item = r.deref(h);
                if let Item::Var(var) = item {
                    let name = var.name(h).to_string();
                    if all_names.iter().any(|s| *s == name) {
                        map.insert(name, var.signal_ref());
                    }
                }
            }

            let missing: Vec<String> = all_names
                .into_iter()
                .filter(|s| !map.contains_key(*s))
                .map(|s| s.to_string())
                .collect();
            if !missing.is_empty() {
                return Err(WaveError::Parse(format!(
                    "signal(s) not found in scope '{scope_path}': {}",
                    missing.join(", ")
                )));
            }

            map
        };

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

        let (from, to) = match cycles {
            Some(s) => super::clock::parse_range(s)?,
            None => (0, all_cycles.len()),
        };
        // Output
        if from >= all_cycles.len() {
            // Past end of trace — no cycles to evaluate
            self.emit(&super::output::PropertyOut {
                scope: scope_path.to_string(),
                clock: clock_name.to_string(),
                expr: eval.to_string(),
                cycles: crate::SerdeRange(from..=from),
                total_cycles: all_cycles.len(),
                n_cycles: 0,
                matches: Vec::new(),
            });
            return Ok(());
        }
        let to = to.min(all_cycles.len());
        if from >= to {
            return Err(WaveError::Parse(format!(
                "--cycles {from}-{to}: from >= to"
            )));
        }
        let cycles = &all_cycles[from..to];

        let read_signal = |name: &str, cycle_idx: usize| -> bool {
            if let Some(sig) = signal_sigs.get(name) {
                let (tt_idx, _) = cycles[cycle_idx];
                super::signals::read_bit(sig, tt_idx as u32)
            } else {
                false
            }
        };

        let matches = super::expr::eval::eval_temporal(&ast, cycles, &read_signal);

        self.emit(&super::output::PropertyOut {
            scope: scope_path.to_string(),
            clock: clock_name.to_string(),
            expr: eval.to_string(),
            cycles: crate::SerdeRange(from..=to),
            total_cycles: all_cycles.len(),
            n_cycles: cycles.len(),
            matches,
        });

        Ok(())
    }
}
