use wellen::ItemRef;

use crate::WaveError;

impl crate::Pulse {
    /// List signals (vars) within a scope, with optional name filter.
    /// Use `--scope -` to read the scope path from stdin (for piping).
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

        if self.json {
            let output = serde_json::json!({
                "scope": scope_path,
                "signals": signals,
            });
            println!("{}", serde_json::to_string(&output).unwrap_or_default());
        } else {
            for name in &signals {
                println!("{name}");
            }
        }

        Ok(())
    }
}
