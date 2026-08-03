use std::collections::BTreeMap;

use wellen::{Item, ItemRef};

use crate::WaveError;

/// Return the single top-level scope name. Errors if there are zero or multiple.
pub(super) fn top_scope(h: &wellen::Hierarchy) -> Result<String, WaveError> {
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
pub(super) fn find_scope(h: &wellen::Hierarchy, path: &str) -> Option<wellen::ScopeRef> {
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

/// Resolve signal names in a scope to their `SignalRef`s.
/// Errors if the scope is missing or any requested signal is not found.
pub(super) fn resolve_signals(
    h: &wellen::Hierarchy,
    scope_path: &str,
    names: &[String],
) -> Result<BTreeMap<String, wellen::SignalRef>, WaveError> {
    let target = match find_scope(h, scope_path) {
        Some(sr) => sr,
        None => return Err(WaveError::Parse(format!("scope '{scope_path}' not found"))),
    };

    let mut map = BTreeMap::new();
    for r in h[target].items(h) {
        let item = r.deref(h);
        if let Item::Var(var) = item {
            let name = var.name(h).to_string();
            if names.iter().any(|s| *s == name) {
                map.insert(name, var.signal_ref());
            }
        }
    }

    let missing: Vec<String> = names
        .iter()
        .filter(|s| !map.contains_key(*s))
        .cloned()
        .collect();
    if !missing.is_empty() {
        return Err(WaveError::Parse(format!(
            "signal(s) not found in scope '{scope_path}': {}",
            missing.join(", ")
        )));
    }

    Ok(map)
}
