//! Generic AST traversal for event expressions.
//!
//! Every pass over the expression tree (normalization, stdlib desugaring,
//! function validation, signal collection) shares the same node skeleton;
//! these two walkers factor it out so a pass only states what it does *to a
//! node*, not how to reach it.

use super::ast::{Expr, SequenceStep};

/// Bottom-up transform: `f` is applied to every node after its children.
///
/// The callback receives the (already transformed) node and returns the
/// replacement. Note that the replacement itself is not re-traversed —
/// passes that can introduce new nodes needing transformation must handle
/// that inside the callback (e.g. `normalize` recursing into an event
/// definition).
pub(crate) fn map(expr: &Expr, f: &mut dyn FnMut(&Expr) -> Expr) -> Expr {
    let mapped = match expr {
        Expr::And(a, b) => Expr::And(Box::new(map(a, f)), Box::new(map(b, f))),
        Expr::Or(a, b) => Expr::Or(Box::new(map(a, f)), Box::new(map(b, f))),
        Expr::Not(a) => Expr::Not(Box::new(map(a, f))),
        Expr::Signal(_) | Expr::Const(_) => expr.clone(),
        Expr::Repeat(a, n) => Expr::Repeat(Box::new(map(a, f)), *n),
        Expr::FirstAfter(a, b) => {
            Expr::FirstAfter(Box::new(map(a, f)), Box::new(map(b, f)))
        }
        Expr::FixedDelay(a, n, b) => {
            Expr::FixedDelay(Box::new(map(a, f)), *n, Box::new(map(b, f)))
        }
        Expr::Window(a, n, m, b) => {
            Expr::Window(Box::new(map(a, f)), *n, *m, Box::new(map(b, f)))
        }
        Expr::Overlapping(a, b) => {
            Expr::Overlapping(Box::new(map(a, f)), Box::new(map(b, f)))
        }
        Expr::Interval(a, b) => Expr::Interval(Box::new(map(a, f)), Box::new(map(b, f))),
        Expr::Implication(a, b) => {
            Expr::Implication(Box::new(map(a, f)), Box::new(map(b, f)))
        }
        Expr::Cmp(a, op, b) => Expr::Cmp(Box::new(map(a, f)), *op, Box::new(map(b, f))),
        Expr::Call(name, args) => Expr::Call(
            name.clone(),
            args.iter().map(|a| map(a, f)).collect(),
        ),
        Expr::Sequence(steps) => Expr::Sequence(
            steps
                .iter()
                .map(|s| SequenceStep {
                    expr: Box::new(map(&s.expr, f)),
                    delay: s.delay,
                })
                .collect(),
        ),
    };
    f(&mapped)
}

/// Pre-order visit: `f` is applied to every node before its children.
/// The callback propagates errors; use it when a pass can reject a tree
/// (e.g. unknown function names).
pub(crate) fn visit(expr: &Expr, f: &mut dyn FnMut(&Expr) -> Result<(), String>) -> Result<(), String> {
    f(expr)?;
    match expr {
        Expr::And(a, b)
        | Expr::Or(a, b)
        | Expr::FirstAfter(a, b)
        | Expr::Overlapping(a, b)
        | Expr::Interval(a, b)
        | Expr::Implication(a, b)
        | Expr::Cmp(a, _, b) => {
            visit(a, f)?;
            visit(b, f)
        }
        Expr::Not(a) | Expr::Repeat(a, _) => visit(a, f),
        Expr::FixedDelay(a, _, b) | Expr::Window(a, _, _, b) => {
            visit(a, f)?;
            visit(b, f)
        }
        Expr::Sequence(steps) => {
            for s in steps {
                visit(&s.expr, f)?;
            }
            Ok(())
        }
        Expr::Call(_, args) => {
            for a in args {
                visit(a, f)?;
            }
            Ok(())
        }
        Expr::Signal(_) | Expr::Const(_) => Ok(()),
    }
}
