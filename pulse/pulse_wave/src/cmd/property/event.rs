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
    match expr {
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
        Expr::And(a, b) => Expr::And(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Or(a, b) => Expr::Or(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Not(a) => Expr::Not(Box::new(normalize(a, ns, events))),
        Expr::Repeat(a, n) => Expr::Repeat(Box::new(normalize(a, ns, events)), *n),
        Expr::FirstAfter(a, b) => Expr::FirstAfter(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::FixedDelay(a, n, b) => Expr::FixedDelay(
            Box::new(normalize(a, ns, events)),
            *n,
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Within(a, n, b) => Expr::Within(
            Box::new(normalize(a, ns, events)),
            *n,
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Overlapping(a, b) => Expr::Overlapping(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Interval(a, b) => Expr::Interval(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Implication(a, b) => Expr::Implication(
            Box::new(normalize(a, ns, events)),
            Box::new(normalize(b, ns, events)),
        ),
        Expr::Sequence(steps) => Expr::Sequence(
            steps
                .iter()
                .map(|s| super::expr::ast::SequenceStep {
                    expr: Box::new(normalize(&s.expr, ns, events)),
                    delay: s.delay,
                })
                .collect(),
        ),
    }
}

/// Collect (namespace, signal name) pairs from a normalized AST.
/// `namespace` is `None` for the main scope, `Some(set)` for event sets.
pub(super) fn collect_signal_refs(expr: &Expr, out: &mut Vec<(Option<String>, String)>) {
    match expr {
        Expr::Signal(name) => {
            if let Some((ns, local)) = name.split_once('.') {
                out.push((Some(ns.to_string()), local.to_string()));
            } else {
                out.push((None, name.clone()));
            }
        }
        Expr::And(a, b)
        | Expr::Or(a, b)
        | Expr::FirstAfter(a, b)
        | Expr::Overlapping(a, b)
        | Expr::Interval(a, b)
        | Expr::Implication(a, b) => {
            collect_signal_refs(a, out);
            collect_signal_refs(b, out);
        }
        Expr::Not(a) | Expr::Repeat(a, _) => collect_signal_refs(a, out),
        Expr::FixedDelay(a, _, b) | Expr::Within(a, _, b) => {
            collect_signal_refs(a, out);
            collect_signal_refs(b, out);
        }
        Expr::Sequence(steps) => {
            for s in steps {
                collect_signal_refs(&s.expr, out);
            }
        }
    }
}
