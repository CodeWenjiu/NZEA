use serde::Serialize;
use wellen::ItemRef;

use crate::WaveError;

/// A signal with its bit width, so rename-map keys (VCD refs like `[16:0]`)
/// and constant-folding detection can be derived from `signal` output alone.
#[derive(Serialize)]
struct SignalInfo {
    name: String,
    width: u32,
    /// Multi-bit vector (width > 1); 1-bit scalars render as plain 0/1.
    vector: bool,
}

#[derive(Serialize)]
struct SignalOut {
    scope: String,
    signals: Vec<SignalInfo>,
}

impl std::fmt::Display for SignalOut {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for s in &self.signals {
            writeln!(f, "{}", s.name)?;
        }
        Ok(())
    }
}

impl crate::Pulse {
    pub(crate) fn signal(&self, scope_path: &str) -> Result<(), WaveError> {
        let h = self.wav.hierarchy();

        let target = super::hierarchy::find_scope_unique(h, scope_path)?;

        let signals: Vec<SignalInfo> = h[target]
            .items(h)
            .filter_map(|r| {
                if let ItemRef::Var(vr) = r {
                    let var = &h[vr];
                    let width = var.index().map(|vi| vi.width()).unwrap_or(1);
                    Some(SignalInfo {
                        name: r.name(h).to_string(),
                        width,
                        vector: width > 1,
                    })
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
