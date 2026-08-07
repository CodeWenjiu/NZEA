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

/// Resolve a scope path to a `ScopeRef`, either by exact dot-path or, when
/// the path contains `*`, by a glob pattern that must match **exactly one**
/// scope in the whole hierarchy. This keeps scope references robust against
/// hierarchy changes (tile-level vs. core-level dumps) without hardcoding
/// paths.
pub(super) fn find_scope_unique(
    h: &wellen::Hierarchy,
    path: &str,
) -> Result<wellen::ScopeRef, WaveError> {
    if path.contains('*') {
        let mut paths = Vec::new();
        collect_scope_paths(h, h.items(), String::new(), &mut paths);
        let hits: Vec<&String> = paths.iter().filter(|p| glob_match(path, p)).collect();
        match hits.len() {
            0 => Err(WaveError::Parse(format!(
                "no scope matches pattern '{path}'"
            ))),
            1 => find_scope(h, hits[0]).ok_or_else(|| {
                WaveError::Parse(format!("scope '{}' matched but not found", hits[0]))
            }),
            n => Err(WaveError::Parse(format!(
                "scope pattern '{path}' matches {n} scopes: {}",
                hits.iter()
                    .map(|s| s.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            ))),
        }
    } else {
        find_scope(h, path).ok_or_else(|| {
            WaveError::Parse(format!(
                "scope '{path}' not found (use '*' for pattern matching)"
            ))
        })
    }
}

fn collect_scope_paths(
    h: &wellen::Hierarchy,
    items: impl Iterator<Item = ItemRef>,
    prefix: String,
    out: &mut Vec<String>,
) {
    for r in items {
        if let ItemRef::Scope(sr) = r {
            let name = r.name(h).to_string();
            let full = if prefix.is_empty() {
                name.clone()
            } else {
                format!("{prefix}.{name}")
            };
            out.push(full.clone());
            let children = h[sr].items(h);
            collect_scope_paths(h, children, full, out);
        }
    }
}

/// Glob match supporting `*` (any run of characters) only.
///
/// The pattern is split on `*`; the first segment must match the string
/// prefix, the last non-empty segment must match the suffix, and every
/// intermediate segment must appear in order.
fn glob_match(pattern: &str, s: &str) -> bool {
    let mut segments = pattern.split('*').peekable();
    let first = segments.next().unwrap_or("");
    if !s.starts_with(first) {
        return false;
    }
    let mut rest = &s[first.len()..];
    while let Some(seg) = segments.next() {
        if seg.is_empty() {
            continue; // consecutive or trailing '*' — no constraint
        }
        if segments.peek().is_none() {
            // Final segment: must anchor the end of the string.
            return rest.ends_with(seg);
        }
        match rest.find(seg) {
            Some(idx) => rest = &rest[idx + seg.len()..],
            None => return false,
        }
    }
    true
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
    let target = find_scope_unique(h, scope_path)?;

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

#[cfg(test)]
mod tests {
    use super::glob_match;

    #[test]
    fn glob() {
        assert!(glob_match("*icache", "TOP.NzeaTile.icache"));
        assert!(glob_match("*.core.*", "TOP.NzeaTile.core.ifu"));
        assert!(glob_match("TOP.*", "TOP.NzeaTile"));
        assert!(glob_match("*", "TOP"));
        assert!(glob_match("a*b", "axxxb"));
        assert!(!glob_match("*dcache", "TOP.NzeaTile.icache"));
        assert!(!glob_match("*.core.*", "TOP.NzeaTile.icache"));
        assert!(!glob_match("TOP.*", "TOP"));
        assert!(!glob_match("a*b", "axxxbyyy"));
    }
}
