use super::ast::{CmpOp, Expr};
use crate::SerdeRange;

/// Evaluate an AST over a series of clock cycles.
///
/// `cycles` is the list of posedge clock timestamps.
/// `read_signal(name, tt_idx)` returns the boolean value of a signal at a given time-table index.
/// `read_value(name, tt_idx)` returns the integer value of a signal (for comparisons);
/// `None` means unknown (X/Z, missing, or non-numeric).
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

/// Number of cycles in the trace.
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
            // Compare the integer values of both operands cycle by cycle.
            // An unknown operand (None) makes the comparison false for that cycle.
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
        Expr::Call(name, _args) => {
            // `stdlib::desugar` expands every call before evaluation, so no
            // Call should ever reach here; a miss is a desugar bug.
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
                if b_bool[ci] {
                    if let Some(_a_ci) = pending_a.first().copied() {
                        matches.push(cycles[ci].1);
                        pending_a.remove(0);
                    }
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
            // matching on A's cycle. The future half reads ahead in the
            // already-loaded waveform; the past half saturates at the
            // window start.
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
                if b_bool[ci] {
                    if let Some(a_ci) = pending_a.first().copied() {
                        intervals.push((cycles[a_ci].1, cycles[ci].1));
                        pending_a.remove(0);
                    }
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
            // Evaluate each step to a bool array
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
            for ci in 0..n {
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
                    matches.push(cycles[ci].1);
                }
            }
            EvalOut::Matches(matches)
        }
    }
}

/// Convert an `EvalOut` to a boolean array indexed by cycle position.
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

/// Evaluate an expression directly to a bool array (for leaf or recursive use).
fn to_bool_array_direct(
    ast: &Expr,
    cycles: &[(usize, u64)],
    read_signal: &dyn Fn(&str, usize) -> bool,
    read_value: &dyn Fn(&str, usize) -> Option<u64>,
) -> Vec<bool> {
    let out = eval_impl(ast, cycles, read_signal, read_value);
    to_bool_array(&out, cycles.len(), cycles)
}

/// Value of an operand at cycle `ci` for a comparison: literals as-is,
/// signals via `read_value`, composite expressions as 0/1 boolean.
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
    /// Cycle i has timestamp 100*i and signal value `sig[i]`.
    fn eval_expr(expr: &str, sig: &[bool]) -> Vec<SerdeRange<u64>> {
        eval_expr_multi(expr, &[("a", sig)])
    }

    /// Like `eval_expr`, but with named signals: `(name, values)` pairs.
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
}
