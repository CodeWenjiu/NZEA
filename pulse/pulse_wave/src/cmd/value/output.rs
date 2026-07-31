use serde::Serialize;
use serde::ser::SerializeMap;

pub(super) struct ValueOut {
    pub(super) scope: String,
    pub(super) signal_names: Vec<String>,
    pub(super) samples: Vec<Sample>,
}

pub(super) struct Sample {
    pub(super) time: u64,
    pub(super) values: Vec<Val>,
}

pub(super) enum Val {
    Bit(bool),
    Hex(String),
    Unknown,
}

impl Serialize for Val {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Val::Bit(b) => s.serialize_u8(if *b { 1 } else { 0 }),
            Val::Hex(h) => s.serialize_str(h),
            Val::Unknown => s.serialize_str("?"),
        }
    }
}

impl std::fmt::Display for Val {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Val::Bit(b) => write!(f, "{}", if *b { '1' } else { '0' }),
            Val::Hex(h) => write!(f, "{h}"),
            Val::Unknown => write!(f, "?"),
        }
    }
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
                    let val: serde_json::Value = match v {
                        Val::Bit(false) => 0.into(),
                        Val::Bit(true) => 1.into(),
                        Val::Hex(h) => h.clone().into(),
                        Val::Unknown => "?".into(),
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
