use serde::Serialize;
use wellen::TimescaleUnit;

use crate::WaveError;

/// Newtype for wellen::Timescale with Display + Serialize.
#[derive(Copy, Clone)]
struct TimeScale(wellen::Timescale);

impl Serialize for TimeScale {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.collect_str(&format_args!("{} {}", self.0.factor, unit_str(self.0.unit)))
    }
}

impl std::fmt::Display for TimeScale {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} {}", self.0.factor, unit_str(self.0.unit))
    }
}

#[derive(Serialize)]
struct InfoOut {
    time_scale: Option<TimeScale>,
    time_start: u64,
    time_end: u64,
    top: String,
}

impl std::fmt::Display for InfoOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "time_scale:   {}",
            self.time_scale
                .as_ref()
                .map_or("unknown".to_string(), |ts| ts.to_string())
        )?;
        write!(f, "\ntime_start:   {}", self.time_start)?;
        write!(f, "\ntime_end:     {}", self.time_end)?;
        write!(f, "\ntop:          {}", self.top)?;
        Ok(())
    }
}

impl crate::Pulse {
    pub(crate) fn info(&self) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();
        let tt = self.wav.time_table();

        let time_scale = h.timescale().map(TimeScale);
        let time_start = tt.first().copied().unwrap_or(0);
        let time_end = tt.last().copied().unwrap_or(0);
        let top = crate::top_scope(h)?;

        self.emit(&InfoOut {
            time_scale,
            time_start,
            time_end,
            top,
        });
        Ok(())
    }
}

fn unit_str(unit: TimescaleUnit) -> &'static str {
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
