use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

impl crate::Pulse {
    pub(crate) fn value(
        &mut self,
        scope_path: &str,
        at_times: &[SerdeRange<u64>],
        signal_names: &[String],
    ) -> Result<(), WaveError> {
        let last_tick = self.wav.time_table().last().copied().unwrap_or(0);
        let times: Vec<u64> = at_times
            .iter()
            .flat_map(|ts| {
                // Clamp open-ended ranges to the last dump tick
                let end = (*ts.0.end()).min(last_tick);
                SerdeRange(*ts.0.start()..=end).resolve()
            })
            .collect();

        let name_to_sref = {
            let h = self.wav.hierarchy();
            super::super::hierarchy::resolve_signals(h, scope_path, signal_names)?
        };

        let srefs: Vec<SignalRef> = name_to_sref.iter().map(|(_, sr)| *sr).collect();
        self.wav.load_signals(&srefs);

        let signals: Vec<(String, &wellen::Signal)> = name_to_sref
            .into_iter()
            .map(|(name, sr)| {
                let sig = self.wav.get_signal(sr).expect("signal not loaded");
                (name, sig)
            })
            .collect();

        let tt = self.wav.time_table();
        let samples: Vec<super::output::Sample> = times
            .iter()
            .map(|&t| {
                let tt_idx: u32 = tt
                    .binary_search(&t)
                    .unwrap_or_else(|i| i.saturating_sub(1))
                    .try_into()
                    .unwrap_or(0);
                let values: Vec<super::output::Val> = signals
                    .iter()
                    .map(|(_, sig)| sig_val(sig, tt_idx))
                    .collect();
                super::output::Sample { time: t, values }
            })
            .collect();

        self.emit(&super::output::ValueOut {
            scope: scope_path.to_string(),
            signal_names: signal_names.to_vec(),
            samples,
        });
        Ok(())
    }
}

fn sig_val(sig: &wellen::Signal, tt_idx: u32) -> super::output::Val {
    let val = sig.get_offset(tt_idx).map(|off| sig.get_value_at(&off, 0));
    match val {
        Some(v) => {
            if v.width() == Some(1) {
                let s = format!("{v}");
                super::output::Val::Bit(s.starts_with('1'))
            } else {
                super::output::Val::Hex(format!("{v}"))
            }
        }
        None => super::output::Val::Unknown,
    }
}
