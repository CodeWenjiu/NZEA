use serde::Serialize;
use wellen::ItemRef;

use crate::WaveError;

#[derive(Serialize)]
struct SignalOut {
    scope: String,
    signals: Vec<String>,
}

impl std::fmt::Display for SignalOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for name in &self.signals {
            writeln!(f, "{name}")?;
        }
        Ok(())
    }
}

impl crate::Pulse {
    pub(crate) fn signal(&self, scope_path: &str) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();

        let target = match super::hierarchy::find_scope(h, scope_path) {
            Some(sr) => sr,
            None => {
                return Err(WaveError::Parse(format!("scope '{scope_path}' not found")));
            }
        };

        let signals: Vec<String> = h[target]
            .items(h)
            .filter_map(|r| {
                if let ItemRef::Var(_) = r {
                    Some(r.name(h).to_string())
                } else {
                    None
                }
            })
            .collect();

        self.emit(&SignalOut {
            scope: scope_path.to_string(),
            signals,
        });
        Ok(())
    }
}
