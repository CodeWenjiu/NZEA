use wellen::TimescaleUnit;

use crate::WaveError;

impl crate::Pulse {
    pub(crate) fn info(&self) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();
        let tt = self.wav.time_table();

        let ts = h.timescale();
        let t_start = tt.first().copied().unwrap_or(0);
        let t_end = tt.last().copied().unwrap_or(0);
        let top_scopes: Vec<String> = h.items().map(|r| r.name(h).to_string()).collect();

        if self.json {
            let ts_obj = ts.map(|ts| {
                serde_json::json!({
                    "factor": ts.factor,
                    "unit": timescale_unit_str(ts.unit),
                })
            });
            let output = serde_json::json!({
                "time_scale": ts_obj,
                "time_start": t_start,
                "time_end": t_end,
                "top_scopes": top_scopes,
            });
            println!("{}", serde_json::to_string(&output).unwrap_or_default());
        } else {
            let ts_str = ts.map_or("unknown".into(), |ts| {
                format!("{} {}", ts.factor, timescale_unit_str(ts.unit))
            });
            println!("time_scale:   {ts_str}");
            println!("time_start:   {t_start}");
            println!("time_end:     {t_end}");
            println!("top scopes:");
            for s in &top_scopes {
                println!("  {s}");
            }
        }

        Ok(())
    }
}

fn timescale_unit_str(unit: TimescaleUnit) -> &'static str {
    match unit {
        TimescaleUnit::Seconds => "s",
        TimescaleUnit::MilliSeconds => "ms",
        TimescaleUnit::MicroSeconds => "us",
        TimescaleUnit::NanoSeconds => "ns",
        TimescaleUnit::PicoSeconds => "ps",
        TimescaleUnit::FemtoSeconds => "fs",
        TimescaleUnit::AttoSeconds => "as",
        TimescaleUnit::ZeptoSeconds => "zs",
        TimescaleUnit::Unknown => "?",
    }
}
