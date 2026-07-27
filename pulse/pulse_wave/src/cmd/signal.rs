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
    pub(crate) fn signal(&self, scope_path: &str, filter: Option<&str>) -> Result<(), WaveError> {
        let scope_path = crate::resolve_scope(scope_path)?;
        let h = self.wav.hierarchy();

        let target = match crate::find_scope(h, &scope_path) {
            Some(sr) => sr,
            None => {
                return Err(WaveError::Parse(format!("scope '{scope_path}' not found")));
            }
        };

        let signals: Vec<String> = h[target]
            .items(h)
            .filter_map(|r| {
                if let ItemRef::Var(_) = r {
                    let name = r.name(h).to_string();
                    if filter.map_or(true, |f| name.contains(f)) {
                        Some(name)
                    } else {
                        None
                    }
                } else {
                    None
                }
            })
            .collect();

        self.emit(&SignalOut {
            scope: scope_path,
            signals,
        });
        Ok(())
    }
}
