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
        Expr::Within(a, n, b) => {
            // A --N B: B within N cycles after A
            let a_bool = to_bool_array_direct(a, cycles, read_signal, read_value);
            let b_bool = to_bool_array_direct(b, cycles, read_signal, read_value);
            let mut matches = Vec::new();
            let n = *n as usize;

            for ci in 0..n_cycles(cycles) {
                if b_bool[ci] {
                    let from = ci.saturating_sub(n);
                    if a_bool[from..=ci].iter().any(|&v| v) {
                        matches.push(cycles[ci].1);
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
