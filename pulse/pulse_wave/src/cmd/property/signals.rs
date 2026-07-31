use super::expr::ast::Expr;

/// Collect all signal names referenced in an AST.
pub(super) fn collect_signals(expr: &Expr) -> Vec<String> {
    let mut names = Vec::new();
    collect_recursive(expr, &mut names);
    names.sort();
    names.dedup();
    names
}

fn collect_recursive(expr: &Expr, out: &mut Vec<String>) {
    match expr {
        Expr::Signal(name) => out.push(name.clone()),
        Expr::And(a, b)
        | Expr::Or(a, b)
        | Expr::FirstAfter(a, b)
        | Expr::Overlapping(a, b)
        | Expr::Interval(a, b)
        | Expr::Implication(a, b) => {
            collect_recursive(a, out);
            collect_recursive(b, out);
        }
        Expr::Not(a) | Expr::Repeat(a, _) => collect_recursive(a, out),
        Expr::FixedDelay(a, _, b) | Expr::Within(a, _, b) => {
            collect_recursive(a, out);
            collect_recursive(b, out);
        }
        Expr::Sequence(steps) => {
            for step in steps {
                collect_recursive(&step.expr, out);
            }
        }
    }
}

/// Read a single-bit value from a signal at a given time index.
pub(super) fn read_bit(sig: &wellen::Signal, tt_idx: u32) -> bool {
    sig.get_offset(tt_idx)
        .map(|off| {
            let v = sig.get_value_at(&off, 0);
            let s = format!("{v}");
            s.chars().next().unwrap_or('0') == '1'
        })
        .unwrap_or(false)
}
