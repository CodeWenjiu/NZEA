use wellen::{ItemRef, simple};

pulse_macro::mod_flat!(cmd, command, error);

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

    pub fn run(mut self, cmd: Command) -> Result<(), WaveError> {
        match cmd {
            Command::Info => self.info(),
            Command::Scope(args) => self.scope(args.filter.as_deref()),
            Command::Signal(args) => self.signal(&args.scope, args.filter.as_deref()),
            Command::Value(args) => self.value(&args.scope, &args.at, &args.signals),
        }
    }
}

// --- shared helpers used by signal / value ---

/// Resolve a scope path: return as-is, or read from stdin if `-`.
fn resolve_scope(scope_path: &str) -> Result<String, WaveError> {
    if scope_path == "-" {
        let mut buf = String::new();
        std::io::stdin()
            .read_line(&mut buf)
            .map_err(WaveError::Io)?;
        let trimmed = buf.trim().to_string();
        if trimmed.is_empty() {
            return Err(WaveError::Parse("no scope path received on stdin".into()));
        }
        Ok(trimmed)
    } else {
        Ok(scope_path.to_string())
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
