use super::ast::{CmpOp, Expr};
use crate::SerdeRange;

/// Evaluate an AST over a series of clock cycles (`cycles` is the list of
/// posedge clock timestamps).
///
/// `read_value` returns `None` for unknown values (X/Z, missing, non-numeric).
pub(crate) fn eval_temporal(
    ast: &Expr,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> Vec<SerdeRange<u64>> {
    match eval_impl(ast, cycles, read_signal, read_value) {
        EvalOut::Matches(ts) => ts.into_iter().map(|t| SerdeRange(t..=t)).collect(),
        EvalOut::Intervals(intervals) => intervals
            .into_iter()
            .map(|(from, to)| SerdeRange(from..=to))
            .collect(),
    }
}

enum EvalOut {
    Matches(Vec<u64>),
    Intervals(Vec<(u64, u64)>),
}

fn n_cycles(cycles: &[(usize, u64)]) -> usize {
    cycles.len()
}

fn eval_impl(
    ast: &Expr,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> EvalOut {
    match ast {
        Expr::Signal(name) => {
            let mut matches = Vec::new();
            for (ci, &(_tt_idx, time)) in cycles.iter().enumerate() {
                if read_signal(name, ci) {
                    matches.push(time);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Const(v) => {
            // A constant as a boolean expression: true when non-zero.
            let mut matches = Vec::new();
            if *v != 0 {
                for &(_tt_idx, time) in cycles {
                    matches.push(time);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Cmp(a, op, b) => {
            // Compare both operands cycle by cycle; an unknown operand (None)
            // makes the comparison false for that cycle.
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                let av = operand_u64(a, ci, cycles, read_signal, read_value);
                let bv = operand_u64(b, ci, cycles, read_signal, read_value);
                if let (Some(av), Some(bv)) = (av, bv)
                    && apply_cmp(*op, av, bv)
                {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Call(name, args) => {
            if name == "change" {
                // Value-edge built-in: the operand's integer value differs from
                // the previous cycle. Compares the full width (multi-bit signals
                // toggle correctly, unlike bit-level `rise`/`fall` which read the
                // MSB). Unknown values (X/Z) neither match nor disturb the hold.
                let mut matches = Vec::new();
                let mut prev: Option<u64> = None;
                for ci in 0..n_cycles(cycles) {
                    let cur = operand_u64(&args[0], ci, cycles, read_signal, read_value);
                    if let (Some(p), Some(c)) = (prev, cur)
                        && p != c
                    {
                        matches.push(cycles[ci].1);
                    }
                    prev = cur;
                }
                return EvalOut::Matches(matches);
            }
            if name == "is_x" {
                // X-visible built-in: matches cycles where the operand's value
                // is unknown (for a bare signal, read_value == None). Composite
                // subexpressions always evaluate to 0/1 and are never X, so
                // `is_x(a && b)` is constantly false — the same convention
                // comparisons use.
                let mut matches = Vec::new();
                for ci in 0..n_cycles(cycles) {
                    if operand_u64(&args[0], ci, cycles, read_signal, read_value).is_none() {
                        matches.push(cycles[ci].1);
                    }
                }
                return EvalOut::Matches(matches);
            }
            // `stdlib::desugar` expands every template call before evaluation,
            // so no other Call should ever reach here; a miss is a desugar bug.
            panic!("unexpanded function call '{name}' reached the evaluator")
        }
        Expr::And(a, b) => {
            let a_matches = eval_impl(a, cycles, read_signal, read_value);
            let b_matches = eval_impl(b, cycles, read_signal, read_value);
            let a_bool = to_bool_array(&a_matches, n_cycles(cycles), cycles);
            let b_bool = to_bool_array(&b_matches, n_cycles(cycles), cycles);
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] && b_bool[ci] {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Or(a, b) => {
            let a_matches = eval_impl(a, cycles, read_signal, read_value);
            let b_matches = eval_impl(b, cycles, read_signal, read_value);
            let a_bool = to_bool_array(&a_matches, n_cycles(cycles), cycles);
            let b_bool = to_bool_array(&b_matches, n_cycles(cycles), cycles);
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] || b_bool[ci] {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Not(a) => {
            let inner = eval_impl(a, cycles, read_signal, read_value);
            let inner_bool = to_bool_array(&inner, n_cycles(cycles), cycles);
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                if !inner_bool[ci] {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::FirstAfter(a, b) => {
            // A -> B: first B after each A (non-overlapping)
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut matches = Vec::new();
            let mut pending_a: Vec<usize> = Vec::new();

            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] {
                    pending_a.push(ci);
                }
                if b_bool[ci]
                    && let Some(_a_ci) = pending_a.first().copied()
                {
                    matches.push(cycles[ci].1);
                    pending_a.remove(0);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Overlapping(a, b) => {
            // A ~> B: each B after A (A persists until matched or superseded)
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut matches = Vec::new();
            let mut has_pending = false;

            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] {
                    has_pending = true;
                }
                if b_bool[ci] && has_pending {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::FixedDelay(a, n, b) => {
            // A ->N B: B exactly N cycles after A
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut matches = Vec::new();
            let n = *n as usize;

            for ci in 0..n_cycles(cycles) {
                if ci >= n && a_bool[ci - n] && b_bool[ci] {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Window(a, n, m, b) => {
            // A n--m B: B within [n cycles before A, m cycles after A],
            // matching on A's cycle; the past half saturates at the window
            // start.
            let n_cycles = n_cycles(cycles);
            if n_cycles == 0 {
                return EvalOut::Matches(Vec::new());
            }
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let n = *n as usize;
            let m = *m as usize;
            let mut matches = Vec::new();
            for (ci, &(_tt_idx, time)) in cycles.iter().enumerate() {
                if a_bool[ci] {
                    let from = ci.saturating_sub(n);
                    let to = (ci + m).min(n_cycles - 1);
                    if b_bool[from..=to].iter().any(|&v| v) {
                        matches.push(time);
                    }
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Interval(a, b) => {
            // A ~~ B: interval from A to B
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut intervals = Vec::new();
            let mut pending_a: Vec<usize> = Vec::new();

            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] {
                    pending_a.push(ci);
                }
                if b_bool[ci]
                    && let Some(a_ci) = pending_a.first().copied()
                {
                    intervals.push((cycles[a_ci].1, cycles[ci].1));
                    pending_a.remove(0);
                }
            }
            EvalOut::Intervals(intervals)
        }
        Expr::Implication(a, b) => {
            // A |-> B: on cycles where A is true, B must also be true (same cycle)
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                if a_bool[ci] && b_bool[ci] {
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Repeat(a, cnt) => {
            // A[N]: true for N consecutive cycles
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let cnt = *cnt as usize;
            let mut matches = Vec::new();
            for ci in 0..n_cycles(cycles) {
                if ci + 1 >= cnt {
                    let start = ci + 1 - cnt;
                    if a_bool[start..=ci].iter().all(|&v| v) {
                        matches.push(cycles[ci].1);
                    }
                }
            }
            EvalOut::Matches(matches)
        }
        Expr::Sequence(steps) => {
            // A >>N B >>M C: pipeline sequence
            if steps.is_empty() {
                return EvalOut::Matches(Vec::new());
            }
            let n = n_cycles(cycles);
            let step_bools: Vec<Vec<bool>> = steps
                .iter()
                .map(|s| to_bool_array_direct(&s.expr, cycles, read_signal, read_value))
                .collect();

            let mut matches = Vec::new();
            let mut cum_delay = 0usize;
            for step in steps.iter() {
                cum_delay += step.delay as usize;
            }

            // A sequence matches at ci if each step_i matches at ci - (remaining delay after step_i)
            for (ci, &(_tt_idx, time)) in cycles.iter().enumerate() {
                let mut ok = true;
                let mut offset = 0usize;
                for (i, step) in steps.iter().enumerate() {
                    offset += step.delay as usize;
                    let target_ci = ci.saturating_sub(cum_delay - offset);
                    if target_ci >= n || !step_bools[i][target_ci] {
                        ok = false;
                        break;
                    }
                }
                if ok {
                    matches.push(time);
                }
            }
            EvalOut::Matches(matches)
        }
    }
}

fn to_bool_array(out: &EvalOut, n: usize, cycles: &[(usize, u64)]) -> Vec<bool> {
    let mut arr = vec![false; n];
    match out {
        EvalOut::Matches(ts) => {
            for t in ts {
                if let Ok(idx) = cycles.binary_search_by_key(t, |&(_, time)| time) {
                    arr[idx] = true;
                }
            }
        }
        EvalOut::Intervals(ints) => {
            for &(_start, end) in ints {
                if let Ok(idx) = cycles.binary_search_by_key(&end, |&(_, time)| time) {
                    arr[idx] = true;
                }
            }
        }
    }
    arr
}

fn to_bool_array_direct(
    ast: &Expr,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> Vec<bool> {
    let out = eval_impl(ast, cycles, read_signal, read_value);
    to_bool_array(&out, cycles.len(), cycles)
}

/// Value of an operand at cycle `ci`: literals as-is, signals via `read_value`,
/// composite expressions as 0/1.
fn operand_u64(
    e: &Expr,
    ci: usize,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> Option<u64> {
    match e {
        Expr::Const(v) => Some(*v),
        Expr::Signal(name) => read_value(name, ci),
        _ => {
            let bools = to_bool_array_direct(e, cycles, read_signal, read_value);
            bools.get(ci).copied().map(|b| b as u64)
        }
    }
}

/// Value-evaluation mode for `--sample`: the scalar value of an expression at
/// one cycle; `None` means unknown (X/Z).
pub(crate) fn eval_value(
    ast: &Expr,
    ci: usize,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> Option<u64> {
    operand_u64(ast, ci, cycles, read_signal, read_value)
}

fn apply_cmp(op: CmpOp, a: u64, b: u64) -> bool {
    match op {
        CmpOp::Eq => a == b,
        CmpOp::Ne => a != b,
        CmpOp::Lt => a < b,
        CmpOp::Le => a <= b,
        CmpOp::Gt => a > b,
        CmpOp::Ge => a >= b,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cmd::property::expr::stdlib;

    /// Full pipeline: parse → desugar → evaluate against a synthetic signal.
    /// Cycle `i` has timestamp `100*i`.
    fn eval_expr(expr: &str, sig: &[bool]) -> Vec<SerdeRange<u64>> {
        eval_expr_multi(expr, &[("a", sig)])
    }

    fn eval_expr_multi(expr: &str, sigs: &[(&str, &[bool])]) -> Vec<SerdeRange<u64>> {
        let ast = super::super::parser::parse(expr).expect("parse");
        let ast = stdlib::desugar(&ast);
        stdlib::check_functions(&ast).expect("check");
        let len = sigs.first().map_or(0, |(_, v)| v.len());
        let cycles: Vec<(usize, u64)> = (0..len).map(|i| (i, 100 * i as u64)).collect();
        let read_signal = |name: &str, ci: usize| -> bool {
            sigs.iter()
                .find(|(n, _)| *n == name)
                .is_some_and(|(_, v)| v[ci])
        };
        let read_value = |name: &str, ci: usize| -> Option<u64> {
            sigs.iter()
                .find(|(n, _)| *n == name)
                .map(|(_, v)| v[ci] as u64)
        };
        eval_temporal(&ast, &cycles, &read_signal, &read_value)
    }

    fn ticks(ranges: &[SerdeRange<u64>]) -> Vec<u64> {
        ranges.iter().map(|r| *r.0.start()).collect()
    }

    #[test]
    fn rise_edges() {
        // a = F T T F T → rising edges at ci=1 and ci=4.
        let out = eval_expr("rise(a)", &[false, true, true, false, true]);
        assert_eq!(ticks(&out), vec![100, 400]);
    }

    #[test]
    fn fall_edges() {
        // a = T T F T F → falling edges at ci=2 and ci=4.
        let out = eval_expr("fall(a)", &[true, true, false, true, false]);
        assert_eq!(ticks(&out), vec![200, 400]);
    }

    #[test]
    fn stable_cycles() {
        // a = T T F F → same as predecessor at ci=1 and ci=3.
        let out = eval_expr("stable(a)", &[true, true, false, false]);
        assert_eq!(ticks(&out), vec![100, 300]);
    }

    #[test]
    fn edges_compose_with_boolean_logic() {
        // rise(a) || fall(a) fires on every change.
        let out = eval_expr("rise(a) || fall(a)", &[true, false, true, true]);
        assert_eq!(ticks(&out), vec![100, 200]);
    }

    #[test]
    fn is_x_reports_unknown_cycles() {
        // a = [0, X, 1, X] → unknown at ci=1 and ci=3. Value-level read: for
        // named signals the read_value closure returns None exactly where the
        // sample is unknown.
        let ast = super::super::parser::parse("is_x(a)").expect("parse");
        let ast = stdlib::desugar(&ast);
        stdlib::check_functions(&ast).expect("check");
        let cycles: Vec<(usize, u64)> = (0..4).map(|i| (i, 100 * i as u64)).collect();
        let read_signal = |name: &str, ci: usize| -> bool { name == "a" && matches!(ci, 2) };
        let read_value = |name: &str, ci: usize| -> Option<u64> {
            if name != "a" {
                return None;
            }
            match ci {
                1 | 3 => None, // X
                2 => Some(1),
                _ => Some(0),
            }
        };
        let out = eval_temporal(&ast, &cycles, &read_signal, &read_value);
        assert_eq!(ticks(&out), vec![100, 300]);
    }

    #[test]
    fn is_x_of_composite_is_never_true() {
        let out = eval_expr_multi(
            "is_x(a && b)",
            &[("a", &[true, false]), ("b", &[false, true])],
        );
        assert!(out.is_empty());
    }

    #[test]
    fn is_x_composes_with_temporal_operators() {
        // X on a, then b in the same or next cycle: `is_x(a) ->1 b` fires on
        // the b cycle that follows an X cycle.
        // a = [0, X, 0], b = [0, 0, 1] → b at ci=2 follows X at ci=1.
        let ast = super::super::parser::parse("is_x(a) ->1 b").expect("parse");
        let ast = stdlib::desugar(&ast);
        stdlib::check_functions(&ast).expect("check");
        let cycles: Vec<(usize, u64)> = (0..3).map(|i| (i, 100 * i as u64)).collect();
        let read_signal = |name: &str, ci: usize| -> bool {
            name == "a" && matches!(ci, 1) || name == "b" && ci == 2
        };
        let read_value = |name: &str, ci: usize| -> Option<u64> {
            match (name, ci) {
                ("a", 1) => None, // X
                ("b", 2) => Some(1),
                _ => Some(0),
            }
        };
        let out = eval_temporal(&ast, &cycles, &read_signal, &read_value);
        assert_eq!(ticks(&out), vec![200]);
    }

    #[test]
    fn window_past_side() {
        // a at ci=2; b at ci=0 (2 before) and ci=1 (1 before).
        // a 2--0 b: window [0, 2] → both b hits match a's cycle 2.
        let a = [false, false, true, false];
        let b = [true, true, false, false];
        let out = eval_expr_multi("a 2--0 b", &[("a", &a), ("b", &b)]);
        assert_eq!(ticks(&out), vec![200]);
    }

    #[test]
    fn window_future_side() {
        // a at ci=1; b at ci=2 (1 after) and ci=4 (3 after).
        // a 0--3 b: window [1, 4] → both b hits match a's cycle 1.
        let a = [false, true, false, false, false];
        let b = [false, false, true, false, true];
        let out = eval_expr_multi("a 0--3 b", &[("a", &a), ("b", &b)]);
        assert_eq!(ticks(&out), vec![100]);
    }

    #[test]
    fn window_both_sides_bounds() {
        // a at ci=2; b at ci=0 (2 before), ci=4 (2 after), ci=5 (3 after).
        // a 2--2 b: window [0, 4] → b at 0 and 4 hit, b at 5 misses.
        let a = [false, false, true, false, false, false];
        let b = [true, false, false, false, true, true];
        let out = eval_expr_multi("a 2--2 b", &[("a", &a), ("b", &b)]);
        assert_eq!(ticks(&out), vec![200]);

        // a 2--1 b: window [0, 3] → b at 0 hits, b at 4/5 miss.
        let out = eval_expr_multi("a 2--1 b", &[("a", &a), ("b", &b)]);
        assert_eq!(ticks(&out), vec![200]);
    }

    #[test]
    fn window_future_clamped_at_trace_end() {
        // a at the last cycle with m > 0: the window must not run past the
        // trace end (no panic, no match beyond it).
        let a = [false, false, true];
        let b = [false, false, false];
        let out = eval_expr_multi("a 5--5 b", &[("a", &a), ("b", &b)]);
        assert!(out.is_empty());

        // Same shape but b inside the clamped window: b at ci=2 == a's cycle.
        let b = [false, false, true];
        let out = eval_expr_multi("a 5--5 b", &[("a", &a), ("b", &b)]);
        assert_eq!(ticks(&out), vec![200]);
    }

    #[test]
    fn eval_value_signal_constant_and_compare() {
        let ast = super::super::parser::parse("a == 5").expect("parse");
        let ast = stdlib::desugar(&ast);
        let cycles: Vec<(usize, u64)> = (0..2).map(|i| (i, 100 * i as u64)).collect();
        let read_signal = |_: &str, _: usize| false;
        let read_value = |name: &str, ci: usize| -> Option<u64> {
            if name == "a" {
                Some(if ci == 0 { 5 } else { 6 })
            } else {
                None
            }
        };
        assert_eq!(
            super::eval_value(&ast, 0, &cycles, &read_signal, &read_value),
            Some(1)
        );
        assert_eq!(
            super::eval_value(&ast, 1, &cycles, &read_signal, &read_value),
            Some(0)
        );

        let ast = super::super::parser::parse("a").expect("parse");
        assert_eq!(
            super::eval_value(&ast, 0, &cycles, &read_signal, &read_value),
            Some(5)
        );
        // Unknown signal value (X) → None.
        let read_value_x = |name: &str, ci: usize| -> Option<u64> {
            if name == "a" && ci == 0 {
                None
            } else {
                Some(0)
            }
        };
        assert_eq!(
            super::eval_value(&ast, 0, &cycles, &read_signal, &read_value_x),
            None
        );
    }
}
