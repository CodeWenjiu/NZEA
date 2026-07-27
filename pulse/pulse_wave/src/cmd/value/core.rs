use serde::Serialize;
use serde::ser::SerializeMap;
use wellen::{Item, SignalRef};

use crate::WaveError;

struct ValueOut {
    scope: String,
    signal_names: Vec<String>,
    samples: Vec<Sample>,
}

struct Sample {
    time: u64,
    values: Vec<String>,
}

impl Serialize for ValueOut {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        let samples: Vec<serde_json::Value> = self
            .samples
            .iter()
            .map(|row| {
                let mut map = serde_json::Map::new();
                map.insert("time".into(), row.time.into());
                for (i, v) in row.values.iter().enumerate() {
                    let val: serde_json::Value = if v == "0" || v == "1" {
                        serde_json::Value::Number(v.parse::<u8>().unwrap().into())
                    } else {
                        v.clone().into()
                    };
                    map.insert(self.signal_names[i].clone(), val);
                }
                serde_json::Value::Object(map)
            })
            .collect();

        let mut out = s.serialize_map(Some(3))?;
        out.serialize_entry("scope", &self.scope)?;
        out.serialize_entry("signals", &self.signal_names)?;
        out.serialize_entry("samples", &samples)?;
        out.end()
    }
}

impl std::fmt::Display for ValueOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "time")?;
        for sig in &self.signal_names {
            write!(f, "  {sig}")?;
        }
        writeln!(f)?;
        for row in &self.samples {
            write!(f, "{}", row.time)?;
            for v in &row.values {
                write!(f, "  {v}")?;
            }
            writeln!(f)?;
        }
        Ok(())
    }
}

impl crate::Pulse {
    pub(crate) fn value(
        &mut self,
        scope_path: &str,
        at_times: &[String],
        signal_names: &[String],
    ) -> Result<(), WaveError> {
        let scope_path = crate::resolve_scope(scope_path)?;

        let times: Vec<u64> = at_times
            .iter()
            .map(|t| crate::expr::eval::parse_time(t, &self.wav))
            .collect::<Result<Vec<_>, _>>()?;

        let name_to_sref = {
            let h = self.wav.hierarchy();
            let target = match crate::find_scope(h, &scope_path) {
                Some(sr) => sr,
                None => {
                    return Err(WaveError::Parse(format!("scope '{scope_path}' not found")));
                }
            };

            let mut map: Vec<(String, SignalRef)> = Vec::new();
            for r in h[target].items(h) {
                let item = r.deref(h);
                if let Item::Var(var) = item {
                    let name = var.name(h).to_string();
                    if signal_names.iter().any(|s| s == &name) {
                        map.push((name, var.signal_ref()));
                    }
                }
            }

            let missing: Vec<String> = signal_names
                .iter()
                .filter(|s| !map.iter().any(|(n, _)| n == s.as_str()))
                .cloned()
                .collect();
            if !missing.is_empty() {
                return Err(WaveError::Parse(format!(
                    "signal(s) not found in scope '{scope_path}': {}",
                    missing.join(", ")
                )));
            }

            map
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
        let samples: Vec<Sample> = times
            .iter()
            .map(|&t| {
                let tt_idx: u32 = tt
                    .binary_search(&t)
                    .unwrap_or_else(|i| i.saturating_sub(1))
                    .try_into()
                    .unwrap_or(0);
                let values: Vec<String> = signals
                    .iter()
                    .map(|(_, sig)| fmt_val(sig, tt_idx))
                    .collect();
                Sample { time: t, values }
            })
            .collect();

        self.emit(&ValueOut {
            scope: scope_path,
            signal_names: signal_names.to_vec(),
            samples,
        });
        Ok(())
    }
}

fn fmt_val(sig: &wellen::Signal, tt_idx: u32) -> String {
    let val = sig.get_offset(tt_idx).map(|off| sig.get_value_at(&off, 0));
    match val {
        Some(v) => {
            if v.width() == Some(1) {
                let s = format!("{v}");
                s.chars().next().unwrap_or('?').to_string()
            } else {
                format!("{v}")
            }
        }
        None => "?".into(),
    }
}
