//! Shared event-engine entry point: [`compile`] (parse → event-name
//! normalization → stdlib check/desugar) and [`eval_matches`] (compile +
//! evaluate over a cycle window, returning tick-domain matches).
//!
//! One expression pipeline serves every consumer (`property` `--eval`/`--sample`,
//! `plot --mark`, `diff --eval`, `value --at match(...)`), so there is exactly
//! one place that knows how an expression string becomes an executable AST and
//! one place that runs it against a waveform.

use std::collections::BTreeMap;

use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

use super::property::expr::ast::Expr;

/// `events` maps qualified event names (`set.name`) to their definitions;
/// pass an empty map (with `ns = ""`) for plain expressions. `ns` selects the
/// namespace used to qualify bare signal names during normalization.
pub(crate) fn compile(
    expr: &str,
    events: &BTreeMap<String, Expr>,
    ns: &str,
) -> Result<Expr, WaveError> {
    let ast = super::property::expr::parser::parse(expr)?;
    let ast = super::property::event::normalize(&ast, ns, events);
    super::property::expr::stdlib::check_functions(&ast).map_err(WaveError::Parse)?;
    Ok(super::property::expr::stdlib::desugar(&ast))
}

/// Compile `expr` and evaluate it over the cycle window `win` (a
/// `build_cycles` slice), returning tick-domain match ranges. All signals the
/// expression references must already be resolved in `name_to_sref` and
/// loaded into `wav`.
pub(crate) fn eval_matches(
    wav: &wellen::simple::Waveform,
    win: &[(usize, u64)],
    name_to_sref: &BTreeMap<String, SignalRef>,
    expr: &str,
    events: &BTreeMap<String, Expr>,
    ns: &str,
) -> Result<Vec<SerdeRange<u64>>, WaveError> {
    let ast = compile(expr, events, ns)?;
    eval_ast(wav, win, name_to_sref, &ast)
}

/// Evaluate an already-compiled AST over a window (the second half of
/// [`eval_matches`]); exposed so callers that hold a compiled AST — and its
/// signal closures — don't compile twice.
pub(crate) fn eval_ast(
    wav: &wellen::simple::Waveform,
    win: &[(usize, u64)],
    name_to_sref: &BTreeMap<String, SignalRef>,
    ast: &Expr,
) -> Result<Vec<SerdeRange<u64>>, WaveError> {
    let signal_sigs: BTreeMap<String, &wellen::Signal> = name_to_sref
        .iter()
        .map(|(n, sr)| {
            let sig = wav.get_signal(*sr).expect("signal not loaded");
            (n.clone(), sig)
        })
        .collect();

    let read_signal = |name: &str, ci: usize| -> bool {
        if let Some(sig) = signal_sigs.get(name) {
            let (ti, _) = win[ci];
            crate::read::read_bit(sig, ti as u32)
        } else {
            false
        }
    };
    let read_value = |name: &str, ci: usize| -> Option<u64> {
        if let Some(sig) = signal_sigs.get(name) {
            let (ti, _) = win[ci];
            crate::read::read_u64(sig, ti as u32)
        } else {
            None
        }
    };

    Ok(super::property::expr::eval::eval_temporal(
        ast,
        win,
        &read_signal,
        &read_value,
    ))
}
