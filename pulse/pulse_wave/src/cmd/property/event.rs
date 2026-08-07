use std::collections::BTreeMap;
use std::path::Path;

use super::expr::ast::Expr;
use crate::WaveError;

/// Parse an event source into named definitions.
/// SOURCE is either a `.pulse` file (one `name = expr` per line) or an inline
/// `name = expr` string.
pub(super) fn load(source: &str) -> Result<Vec<(String, Expr)>, WaveError> {
    if Path::new(source).exists() {
        let content = std::fs::read_to_string(source).map_err(|e| WaveError::Io(e))?;
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
        // Inline: "name = expr"
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
pub(super) fn normalize(expr: &Expr, ns: &str, events: &BTreeMap<String, Expr>) -> Expr {
    super::expr::walk::map(expr, &mut |e| match e {
        Expr::Signal(name) => {
            if name.contains('.') {
                // Qualified: either an event reference or a namespaced signal
                if let Some(def) = events.get(name) {
                    // Substitute with the definition, normalized in ITS own namespace
                    let set_ns = name.split('.').next().unwrap_or("").to_string();
                    return normalize(def, &set_ns, events);
                }
                // Namespaced signal: keep as-is
                Expr::Signal(name.clone())
            } else if let Some(def) = events.get(&format!("{ns}.{name}")) {
                // Bare name matching an event in the current namespace
                normalize(def, ns, events)
            } else if ns.is_empty() {
                Expr::Signal(name.clone())
            } else {
                // Bare signal in a non-main namespace → qualify
                Expr::Signal(format!("{ns}.{name}"))
            }
        }
        _ => e.clone(),
    })
}

/// Collect (namespace, signal name) pairs from a normalized AST.
/// `namespace` is `None` for the main scope, `Some(set)` for event sets.
pub(super) fn collect_signal_refs(expr: &Expr, out: &mut Vec<(Option<String>, String)>) {
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
