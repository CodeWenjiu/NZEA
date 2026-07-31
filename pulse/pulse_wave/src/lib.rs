use wellen::{ItemRef, simple};

pulse_macro::mod_flat!(command, error, time_spec, tree);
pulse_macro::mod_pub!(cmd);

pub struct Pulse {
    wav: simple::Waveform,
    json: bool,
}

impl Pulse {
    pub fn new(cli: &PulseCommand) -> Result<Self, WaveError> {
        let wav = simple::read(&cli.wave)?;
        Ok(Self {
            wav,
            json: cli.json,
        })
    }

    fn emit<T: serde::Serialize + std::fmt::Display>(&self, out: &T) {
        if self.json {
            println!("{}", serde_json::to_string(out).unwrap_or_default());
        } else {
            println!("{out}");
        }
    }

    pub fn run(mut self, cmd: Command) -> Result<(), WaveError> {
        match cmd {
            Command::Info => self.info(),
            Command::Scope(args) => self.scope(
                args.depth,
                args.filter.as_deref(),
                args.flat,
                args.root.as_deref(),
            ),
            Command::Signal(args) => self.signal(&args.scope),
            Command::Value(args) => self.value(&args.scope, &args.at, &args.signals),
            Command::Property(args) => {
                self.property(&args.scope, &args.on, &args.eval, args.cycles.as_deref())
            }
        }
    }
}

// --- shared helpers ---

/// Return the single top-level scope name. Errors if there are zero or multiple.
fn top_scope(h: &wellen::Hierarchy) -> Result<String, WaveError> {
    let scopes: Vec<String> = h
        .items()
        .filter_map(|r| {
            if matches!(r, ItemRef::Scope(_)) {
                Some(r.name(h).to_string())
            } else {
                None
            }
        })
        .collect();
    match scopes.len() {
        0 => Err(WaveError::Parse("no top-level scope found".into())),
        1 => Ok(scopes.into_iter().next().unwrap()),
        n => Err(WaveError::Parse(format!(
            "expected exactly 1 top-level scope, found {n}: {}",
            scopes.join(", ")
        ))),
    }
}

/// Walk the hierarchy looking for a scope matching a dot-separated path.
fn find_scope(h: &wellen::Hierarchy, path: &str) -> Option<wellen::ScopeRef> {
    let parts: Vec<&str> = path.split('.').collect();
    find_scope_inner(h, h.items(), &parts, 0)
}

fn find_scope_inner(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    parts: &[&str],
    idx: usize,
) -> Option<wellen::ScopeRef> {
    if idx >= parts.len() {
        return None;
    }
    for r in items {
        let name = r.name(h);
        if name == parts[idx] {
            if let ItemRef::Scope(sr) = r {
                if idx == parts.len() - 1 {
                    return Some(sr);
                }
                let children = h[sr].items(h);
                return find_scope_inner(h, children, parts, idx + 1);
            }
        }
    }
    None
}
