//! Shared event-engine entry point.
//!
//! One expression pipeline serves every consumer: `property` (`--eval` and
//! `--sample` expressions), `plot --mark`, `diff --eval`, and
//! `value --at match(...)`. It is deliberately two functions:
//!
//! - [`compile`] — parse → event-name normalization → stdlib check/desugar.
//!   Callers that need the AST itself (property collects signal references
//!   from it, and evaluates `--sample` expressions against it) use this.
//! - [`eval_matches`] — compile + evaluate over a cycle window, returning
//!   tick-domain matches. Callers that only want match points use this.
//!
//! Keeping both here means there is exactly one place that knows how an
//! expression string becomes an executable AST, and exactly one place that
//! knows how to run it against a waveform.

use std::collections::BTreeMap;

use wellen::SignalRef;

use crate::SerdeRange;
use crate::WaveError;

use super::property::expr::ast::Expr;

/// Parse, normalize event-set references, validate stdlib calls, desugar.
///
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
/// [`eval_matches`]; exposed so callers like `property` that hold a compiled
/// AST — and its signal closures — don't compile twice).
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
