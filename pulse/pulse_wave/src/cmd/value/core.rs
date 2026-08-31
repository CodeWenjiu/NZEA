use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

/// Radix for rendering multi-bit signal values.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Radix {
    Bin,
    Hex,
    Dec,
    Oct,
}

impl Radix {
    fn from_str(s: &str) -> Result<Self, WaveError> {
        match s.to_ascii_lowercase().as_str() {
            "bin" => Ok(Radix::Bin),
            "hex" => Ok(Radix::Hex),
            "dec" => Ok(Radix::Dec),
            "oct" => Ok(Radix::Oct),
            other => Err(WaveError::Parse(format!(
                "invalid radix '{other}' (expected bin, hex, dec, oct)"
            ))),
        }
    }
}

impl crate::Pulse {
    pub(crate) fn value(
        &mut self,
        scope_path: &str,
        at_times: &[SerdeRange<u64>],
        signal_names: &[String],
        radix: &str,
    ) -> Result<(), WaveError> {
        let radix = Radix::from_str(radix)?;
        let last_tick = self.wav.time_table().last().copied().unwrap_or(0);
        let times: Vec<u64> = at_times
            .iter()
            .flat_map(|ts| {
                // Resolve the `~N` "last N ticks" sentinel against the last tick.
                if let Some(n) = ts.tail_n() {
                    let start = last_tick.saturating_sub(n - 1);
                    return (start..=last_tick).collect::<Vec<u64>>();
                }
                // Clamp open-ended ranges to the last dump tick
                let end = (*ts.0.end()).min(last_tick);
                SerdeRange(*ts.0.start()..=end).resolve()
            })
            .collect();

        let name_to_sref = {
            let h = self.wav.hierarchy();
            super::super::hierarchy::resolve_signals(h, scope_path, signal_names)?
        };

        let srefs: Vec<SignalRef> = name_to_sref.values().copied().collect();
        self.wav.load_signals(&srefs);

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
                    .map(|(_, sig)| sig_val(sig, tt_idx, radix))
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

fn sig_val(sig: &wellen::Signal, tt_idx: u32, radix: Radix) -> super::output::Val {
    let val = sig.get_offset(tt_idx).map(|off| sig.get_value_at(&off, 0));
    match val {
        Some(v) => {
            if v.width() == Some(1) {
                let s = format!("{v}");
                super::output::Val::Bit(s.starts_with('1'))
            } else {
                let s = format!("{v}"); // binary string (wellen Display)
                match radix {
                    Radix::Bin => super::output::Val::Hex(s),
                    Radix::Hex | Radix::Dec | Radix::Oct => {
                        // Convert via u64; fall back to the binary string for
                        // X/Z or values wider than u64.
                        let width = v.width().unwrap_or(32) as usize;
                        match u64::try_from(v) {
                            Ok(n) => {
                                let rendered = match radix {
                                    Radix::Hex => {
                                        let nibbles = width.div_ceil(4).max(1);
                                        format!("{n:0width$x}", width = nibbles)
                                    }
                                    Radix::Dec => format!("{n}"),
                                    Radix::Oct => {
                                        let digits = width.div_ceil(3).max(1);
                                        format!("{n:0width$o}", width = digits)
                                    }
                                    Radix::Bin => unreachable!(),
                                };
                                super::output::Val::Hex(rendered)
                            }
                            Err(_) => super::output::Val::Hex(s),
                        }
                    }
                }
            }
        }
        None => super::output::Val::Unknown,
    }
}
