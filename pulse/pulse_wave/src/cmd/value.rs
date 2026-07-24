use wellen::{Item, SignalRef};

use crate::WaveError;

impl crate::Pulse {
    /// Sample signal values at specific timestamps.
    /// Use `--scope -` to read the scope path from stdin (for piping).
    pub(crate) fn value(
        &mut self,
        scope_path: &str,
        at_times: &[String],
        signal_names: &[String],
    ) -> Result<(), WaveError> {
        let scope_path = crate::resolve_scope(scope_path)?;

        let times: Vec<u64> = at_times
            .iter()
            .map(|t| parse_time(t, &self.wav))
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

        let mut signals: Vec<(&str, &wellen::Signal)> = Vec::new();
        for (name, sr) in &name_to_sref {
            match self.wav.get_signal(*sr) {
                Some(sig) => signals.push((name, sig)),
                None => {
                    return Err(WaveError::Parse(format!("failed to load signal '{name}'")));
                }
            }
        }

        if self.json {
            self.output_json(&scope_path, signal_names, &times, &signals);
        } else {
            self.output_table(&times, &signals);
        }

        Ok(())
    }

    fn output_table(&self, times: &[u64], signals: &[(&str, &wellen::Signal)]) {
        print!("time");
        for (name, _) in signals {
            print!("  {name}");
        }
        println!();

        let tt = self.wav.time_table();
        for &t in times {
            let tt_idx: u32 = tt
                .binary_search(&t)
                .unwrap_or_else(|i| i.saturating_sub(1))
                .try_into()
                .unwrap_or(0);
            print!("{t}");
            for (_, sig) in signals {
                print!("  {}", Self::fmt_val(sig, tt_idx));
            }
            println!();
        }
    }

    fn output_json(
        &self,
        scope: &str,
        signal_names: &[String],
        times: &[u64],
        signals: &[(&str, &wellen::Signal)],
    ) {
        let tt = self.wav.time_table();
        let samples: Vec<serde_json::Value> = times
            .iter()
            .map(|&t| {
                let tt_idx: u32 = tt
                    .binary_search(&t)
                    .unwrap_or_else(|i| i.saturating_sub(1))
                    .try_into()
                    .unwrap_or(0);
                let mut obj = serde_json::Map::new();
                obj.insert("time".into(), t.into());
                for (name, sig) in signals {
                    let val = Self::fmt_val(sig, tt_idx);
                    obj.insert(
                        (*name).into(),
                        if val == "0" || val == "1" {
                            serde_json::Value::Number((val.parse::<u8>().unwrap()).into())
                        } else {
                            val.into()
                        },
                    );
                }
                serde_json::Value::Object(obj)
            })
            .collect();

        let output = serde_json::json!({
            "scope": scope,
            "signals": signal_names,
            "samples": samples,
        });
        println!("{}", serde_json::to_string(&output).unwrap_or_default());
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
}

// --- time parsing ---

fn parse_time(s: &str, wav: &wellen::simple::Waveform) -> Result<u64, WaveError> {
    let s = s.trim();
    if let Ok(n) = s.parse::<u64>() {
        return Ok(n);
    }
    let split_at = s
        .find(|c: char| !c.is_ascii_digit() && c != '.')
        .unwrap_or(s.len());
    let (num_str, unit) = s.split_at(split_at);
    if unit.is_empty() {
        return Err(WaveError::Parse(format!("invalid time: '{s}'")));
    }
    let num: f64 = num_str
        .parse()
        .map_err(|_| WaveError::Parse(format!("invalid time: '{s}'")))?;
    let factor = unit_factor(unit)?;
    let ts = wav
        .hierarchy()
        .timescale()
        .ok_or_else(|| WaveError::Parse("no timescale in waveform".into()))?;
    let ts_secs = timescale_to_seconds(ts.unit, ts.factor as f64);
    Ok((num * factor / ts_secs) as u64)
}

fn unit_factor(unit: &str) -> Result<f64, WaveError> {
    match unit {
        "s" => Ok(1.0),
        "ms" => Ok(1e-3),
        "us" => Ok(1e-6),
        "ns" => Ok(1e-9),
        "ps" => Ok(1e-12),
        "fs" => Ok(1e-15),
        _ => Err(WaveError::Parse(format!("unknown time unit: '{unit}'"))),
    }
}

fn timescale_to_seconds(unit: wellen::TimescaleUnit, factor: f64) -> f64 {
    match unit {
        wellen::TimescaleUnit::Seconds => factor,
        wellen::TimescaleUnit::MilliSeconds => factor * 1e-3,
        wellen::TimescaleUnit::MicroSeconds => factor * 1e-6,
        wellen::TimescaleUnit::NanoSeconds => factor * 1e-9,
        wellen::TimescaleUnit::PicoSeconds => factor * 1e-12,
        wellen::TimescaleUnit::FemtoSeconds => factor * 1e-15,
        wellen::TimescaleUnit::AttoSeconds => factor * 1e-18,
        wellen::TimescaleUnit::ZeptoSeconds => factor * 1e-21,
        wellen::TimescaleUnit::Unknown => 1.0,
    }
}
