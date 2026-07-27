/// Shared evaluation utilities used by `value` and `property`.
use crate::WaveError;

pub fn parse_time(s: &str, wav: &wellen::simple::Waveform) -> Result<u64, WaveError> {
    let s = s.trim();
    if let Ok(n) = s.parse::<u64>() {
        return Ok(n);
    }
    let split_at = s
        .find(|c: char| !c.is_ascii_digit() && c != '.')
        .unwrap_or(s.len());
    let (num_str, unit) = s.split_at(split_at);
    if unit.is_empty() {
        return Err(WaveError::Parse(format!("invalid time: '{}'", s)));
    }
    let num: f64 = num_str
        .parse()
        .map_err(|_| WaveError::Parse(format!("invalid time: '{}'", s)))?;
    let factor = unit_factor(unit)?;
    let ts = wav
        .hierarchy()
        .timescale()
        .ok_or_else(|| WaveError::Parse("no timescale in waveform".into()))?;
    let ts_secs = timescale_to_seconds(ts.unit, ts.factor as f64);
    Ok((num * factor / ts_secs) as u64)
}

fn unit_factor(unit: &str) -> Result<f64, WaveError> {
    match unit {
        "s" => Ok(1.0),
        "ms" => Ok(1e-3),
        "us" => Ok(1e-6),
        "ns" => Ok(1e-9),
        "ps" => Ok(1e-12),
        "fs" => Ok(1e-15),
        _ => Err(WaveError::Parse(format!("unknown time unit: '{}'", unit))),
    }
}

fn timescale_to_seconds(unit: wellen::TimescaleUnit, factor: f64) -> f64 {
    match unit {
        wellen::TimescaleUnit::Seconds => factor,
        wellen::TimescaleUnit::MilliSeconds => factor * 1e-3,
        wellen::TimescaleUnit::MicroSeconds => factor * 1e-6,
        wellen::TimescaleUnit::NanoSeconds => factor * 1e-9,
        wellen::TimescaleUnit::PicoSeconds => factor * 1e-12,
        wellen::TimescaleUnit::FemtoSeconds => factor * 1e-15,
        wellen::TimescaleUnit::AttoSeconds => factor * 1e-18,
        wellen::TimescaleUnit::ZeptoSeconds => factor * 1e-21,
        wellen::TimescaleUnit::Unknown => 1.0,
    }
}

// ── temporal evaluator ─────────────────────────────────────

use super::ast::Expr;

/// Evaluate an AST over a series of clock cycles.
///
/// `cycles` is the list of posedge clock timestamps.
/// `read_signal(name, tt_idx)` returns the boolean value of a signal at a given time-table index.
pub fn eval_temporal(
    ast: &Expr,
    cycles: &[(usize, u64)], // (tt_idx, time)
    read_signal: &dyn Fn(&str, usize) -> bool,
) -> Vec<u64> {
    match eval_impl(ast, cycles, read_signal) {
        EvalOut::Matches(ts) => ts,
        EvalOut::Intervals(intervals) => intervals.into_iter().map(|(_, t)| t).collect(),
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
        Expr::And(a, b) => {
            let a_matches = eval_impl(a, cycles, read_signal);
            let b_matches = eval_impl(b, cycles, read_signal);
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
            let a_matches = eval_impl(a, cycles, read_signal);
            let b_matches = eval_impl(b, cycles, read_signal);
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
            let inner = eval_impl(a, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
            let b_bool = to_bool_array_direct(b, cycles, read_signal);
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
            let a_bool = to_bool_array_direct(a, cycles, read_signal);
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
                .map(|s| to_bool_array_direct(&s.expr, cycles, read_signal))
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
) -> Vec<bool> {
    let out = eval_impl(ast, cycles, read_signal);
    to_bool_array(&out, cycles.len(), cycles)
}
