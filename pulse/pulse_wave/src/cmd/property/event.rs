use std::collections::BTreeMap;
use std::path::Path;

use super::expr::ast::Expr;
use crate::WaveError;

/// Parse an event source into named definitions.
/// SOURCE is either a `.pulse` file (one `name = expr` per line) or an inline
/// `name = expr` string.
pub(super) fn load(source: &str) -> Result<Vec<(String, Expr)>, WaveError> {
    if Path::new(source).exists() {
        let content = std::fs::read_to_string(source).map_err(WaveError::Io)?;
        let mut defs = Vec::new();
        for (i, line) in content.lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("--") {
                continue;
            }
            let def = parse_def(line)
                .map_err(|e| WaveError::Parse(format!("{source}:{}: {e}", i + 1)))?;
            defs.push(def);
        }
        Ok(defs)
    } else {
        Ok(vec![parse_def(source)?])
    }
}

fn parse_def(s: &str) -> Result<(String, Expr), WaveError> {
    let (name, expr_str) = s
        .split_once('=')
        .ok_or_else(|| WaveError::Parse(format!("expected 'name = expr', got '{s}'")))?;
    let name = name.trim().to_string();
    if name.is_empty() || name.contains('.') {
        return Err(WaveError::Parse(format!("invalid event name: '{name}'")));
    }
    let expr = super::expr::parser::parse(expr_str)?;
    Ok((name, expr))
}

/// Substitute event references with their definitions, qualifying bare signal
/// names with the current namespace.
///
/// After normalization, every `Signal` in the AST carries a fully-qualified
/// name: bare names are prefixed with `ns` (or kept bare for the main scope),
/// and event references are replaced by their definition subtrees.
///
/// A reference whose definition is already being expanded stays a signal
/// instead of expanding again: `flushing = flushing` is the natural alias for
/// a signal of that name in the event set's scope, and a definition chain that
/// loops back (`a = b`, `b = a`) must terminate, not recurse forever.
pub(crate) fn normalize(expr: &Expr, ns: &str, events: &BTreeMap<String, Expr>) -> Expr {
    normalize_with(expr, ns, events, &mut Vec::new())
}

/// [`normalize`] with the path of definitions currently open — the guard that
/// turns a back-reference into a signal.
fn normalize_with(
    expr: &Expr,
    ns: &str,
    events: &BTreeMap<String, Expr>,
    expanding: &mut Vec<String>,
) -> Expr {
    super::expr::walk::map(expr, &mut |e| match e {
        Expr::Signal(name) => normalize_signal(name, ns, events, expanding),
        _ => e.clone(),
    })
}

fn normalize_signal(
    name: &str,
    ns: &str,
    events: &BTreeMap<String, Expr>,
    expanding: &mut Vec<String>,
) -> Expr {
    // A qualified name is an event reference looked up as-is; a bare one is
    // looked up under the current namespace.
    let key = if name.contains('.') {
        name.to_string()
    } else {
        format!("{ns}.{name}")
    };
    if !expanding.contains(&key)
        && let Some(def) = events.get(&key)
    {
        // Substitute with the definition, normalized in ITS own namespace:
        // a reference through another set's namespace enters that set.
        let set_ns = key.split('.').next().unwrap_or("").to_string();
        expanding.push(key);
        let out = normalize_with(def, &set_ns, events, expanding);
        expanding.pop();
        return out;
    }
    // Not an expandable event reference, so a signal: bare names take the
    // namespace prefix, already-qualified names keep theirs.
    if name.contains('.') || !ns.is_empty() {
        Expr::Signal(key)
    } else {
        Expr::Signal(name.to_string())
    }
}

/// Collect (namespace, signal name) pairs from a normalized AST.
/// `namespace` is `None` for the main scope, `Some(set)` for event sets.
pub(crate) fn collect_signal_refs(expr: &Expr, out: &mut Vec<(Option<String>, String)>) {
    super::expr::walk::visit(expr, &mut |e| {
        if let Expr::Signal(name) = e {
            if let Some((ns, local)) = name.split_once('.') {
                out.push((Some(ns.to_string()), local.to_string()));
            } else {
                out.push((None, name.clone()));
            }
        }
        Ok(())
    })
    .expect("collect_signal_refs callback is infallible")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sig(name: &str) -> Expr {
        Expr::Signal(name.to_string())
    }

    fn defs(entries: &[(&str, Expr)]) -> BTreeMap<String, Expr> {
        entries.iter().map(|(k, v)| (k.to_string(), v.clone())).collect()
    }

    #[test]
    fn self_named_definition_resolves_to_a_signal() {
        // `flushing = flushing`: the natural alias for the set scope's own
        // signal, not a reason to expand forever.
        let events = defs(&[("W.flushing", sig("flushing"))]);
        assert_eq!(normalize(&sig("W.flushing"), "", &events), sig("W.flushing"));
    }

    #[test]
    fn definition_cycle_terminates() {
        // `a = b`, `b = a`: the back-edge (`W.a` reached from inside `W.b`)
        // resolves as the signal instead of recursing.
        let events = defs(&[("W.a", sig("b")), ("W.b", sig("a"))]);
        assert_eq!(normalize(&sig("W.a"), "", &events), sig("W.a"));
    }

    #[test]
    fn definitions_still_expand_and_qualify() {
        // `taken = resolved && W.mispred` with `mispred = mispred_valid`: one
        // definition expands into another, and bare signals get the set's
        // namespace.
        let events = defs(&[
            (
                "W.taken",
                Expr::And(Box::new(sig("resolved")), Box::new(sig("W.mispred"))),
            ),
            ("W.mispred", sig("mispred_valid")),
        ]);
        let want = Expr::And(Box::new(sig("W.resolved")), Box::new(sig("W.mispred_valid")));
        assert_eq!(normalize(&sig("W.taken"), "", &events), want);
    }

    #[test]
    fn namespace_qualification_matches_the_reading_scope() {
        let events = defs(&[]);
        assert_eq!(normalize(&sig("flushing"), "W", &events), sig("W.flushing"));
        assert_eq!(normalize(&sig("flushing"), "", &events), sig("flushing"));
    }
}
