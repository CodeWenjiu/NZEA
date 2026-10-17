//! `pulse diff` — compare two waveforms cycle by cycle.
//!
//! Both waveforms are sampled under the same cycle convention as
//! `property`/`plot` (posedges of `clock`, or every time-table entry when the
//! scope has no clock). Comparison is aligned **by cycle index**, which
//! tolerates different time origins (e.g. ILA captures with different trigger
//! points, or simulations started at different offsets).

use serde_json::json;

use crate::SerdeRange;
use crate::WaveError;

use super::plot::{self, Val};

pub(crate) fn run_diff(json: bool, args: &crate::command::DiffArgs) -> Result<(), WaveError> {
    if args.signals.is_empty() && args.eval.is_empty() {
        return Err(WaveError::Parse(
            "at least one --signals or --eval is required".into(),
        ));
    }

    let mut wav_a = crate::load_waveform(&args.wave_a)?;
    let mut wav_b = crate::load_waveform(&args.wave_b)?;

    // Default scope: each wave's top-level scope (must exist in both).
    let scope_path = match &args.scope {
        Some(s) => s.clone(),
        None => crate::cmd::hierarchy::top_scope(wav_a.hierarchy())?.to_string(),
    };

    let prep_a = plot::prepare_wave(&mut wav_a, &scope_path, &args.signals)?;
    let prep_b = plot::prepare_wave(&mut wav_b, &scope_path, &args.signals)?;
    let win_a = plot::slice_cycles(&prep_a.cycles, args.cycles.clone())?;
    let win_b = plot::slice_cycles(&prep_b.cycles, args.cycles.clone())?;

    let n = win_a.len().min(win_b.len());
    if n == 0 {
        return Err(WaveError::Parse(
            "empty comparison window on one or both waveforms".into(),
        ));
    }

    let signals: Option<SignalOutcome> = if args.signals.is_empty() {
        None
    } else {
        let sigs_a: Vec<&wellen::Signal> = args
            .signals
            .iter()
            .map(|name| {
                let sr = prep_a.name_to_sref[name];
                wav_a.get_signal(sr).expect("signal not loaded")
            })
            .collect();
        let sigs_b: Vec<&wellen::Signal> = args
            .signals
            .iter()
            .map(|name| {
                let sr = prep_b.name_to_sref[name];
                wav_b.get_signal(sr).expect("signal not loaded")
            })
            .collect();

        let mut first: Option<SignalOutcome> = None;
        for ci in 0..n {
            let mut diverged = Vec::new();
            for (i, name) in args.signals.iter().enumerate() {
                let va = plot::sample(sigs_a[i], win_a[ci].0 as u32);
                let vb = plot::sample(sigs_b[i], win_b[ci].0 as u32);
                if va != vb {
                    diverged.push(SignalDiff {
                        name: name.clone(),
                        a: val_str(va),
                        b: val_str(vb),
                    });
                }
            }
            if !diverged.is_empty() {
                first = Some(SignalOutcome {
                    cycle: ci,
                    tick_a: win_a[ci].1,
                    tick_b: win_b[ci].1,
                    diverged,
                });
                break;
            }
        }
        first
    };

    let evals: Vec<EvalOutcome> = args
        .eval
        .iter()
        .map(|expr| {
            let ma = crate::cmd::engine::eval_matches(
                &wav_a,
                &win_a,
                &prep_a.name_to_sref,
                expr,
                &std::collections::BTreeMap::new(),
                "",
            )?;
            let mb = crate::cmd::engine::eval_matches(
                &wav_b,
                &win_b,
                &prep_b.name_to_sref,
                expr,
                &std::collections::BTreeMap::new(),
                "",
            )?;
            let ba = matches_to_bool(&crate::cycle::local_cycle_ranges(&win_a, &ma), n);
            let bb = matches_to_bool(&crate::cycle::local_cycle_ranges(&win_b, &mb), n);
            let first = (0..n).find(|&ci| ba[ci] != bb[ci]);
            Ok(EvalOutcome {
                expr: expr.clone(),
                first_divergence: first.map(|ci| CycleDiverge {
                    cycle: ci,
                    a: ba[ci],
                    b: bb[ci],
                }),
            })
        })
        .collect::<Result<Vec<_>, WaveError>>()?;

    if json {
        let sigs = match &signals {
            Some(s) => json!({
                "first_divergence": {
                    "cycle": s.cycle,
                    "tick_a": s.tick_a,
                    "tick_b": s.tick_b,
                    "diverged": s.diverged.iter().map(|d| json!({
                        "signal": d.name,
                        "a": d.a,
                        "b": d.b,
                    })).collect::<Vec<_>>(),
                }
            }),
            None => json!({"first_divergence": null}),
        };
        let evals_json: Vec<serde_json::Value> = evals
            .iter()
            .map(|e| match &e.first_divergence {
                Some(f) => json!({
                    "expr": e.expr,
                    "first_divergence": {"cycle": f.cycle, "a": f.a, "b": f.b},
                }),
                None => json!({"expr": e.expr, "first_divergence": null}),
            })
            .collect();
        let out = json!({
            "wave_a": args.wave_a.to_string_lossy(),
            "wave_b": args.wave_b.to_string_lossy(),
            "cycles_a": win_a.len(),
            "cycles_b": win_b.len(),
            "compared_cycles": n,
            "signals": sigs,
            "evals": evals_json,
        });
        println!("{}", serde_json::to_string(&out).unwrap_or_default());
    } else {
        println!("wave A: {} ({} cycles)", args.wave_a.display(), win_a.len());
        println!("wave B: {} ({} cycles)", args.wave_b.display(), win_b.len());
        println!("scope: {scope_path}  (compared {n} cycles)");
        match &signals {
            Some(s) => {
                let desc: Vec<String> = s
                    .diverged
                    .iter()
                    .map(|d| format!("{} ({} vs {})", d.name, d.a, d.b))
                    .collect();
                println!(
                    "signals: first divergence at cycle {} (tick {} vs {}): {}",
                    s.cycle,
                    s.tick_a,
                    s.tick_b,
                    desc.join(", ")
                );
            }
            None => println!("signals: identical"),
        }
        for e in &evals {
            match &e.first_divergence {
                Some(f) => println!(
                    "eval {:?}: first divergence at cycle {} (A={}, B={})",
                    e.expr, f.cycle, f.a, f.b
                ),
                None => println!("eval {:?}: identical", e.expr),
            }
        }
    }
    Ok(())
}

fn val_str(v: Val) -> String {
    v.to_string()
}

fn matches_to_bool(ranges: &[SerdeRange<u64>], n: usize) -> Vec<bool> {
    let mut arr = vec![false; n];
    if n == 0 {
        return arr;
    }
    for r in ranges {
        let from = *r.0.start() as usize;
        let to = *r.0.end() as usize;
        let lo = from.min(n - 1);
        let hi = to.min(n - 1);
        if from > hi {
            continue; // entire range lies past the array end
        }
        arr[lo..=hi].fill(true);
    }
    arr
}

#[derive(Debug)]
struct SignalDiff {
    name: String,
    a: String,
    b: String,
}

#[derive(Debug)]
struct SignalOutcome {
    cycle: usize,
    tick_a: u64,
    tick_b: u64,
    diverged: Vec<SignalDiff>,
}

#[derive(Debug)]
struct CycleDiverge {
    cycle: usize,
    a: bool,
    b: bool,
}

#[derive(Debug)]
struct EvalOutcome {
    expr: String,
    first_divergence: Option<CycleDiverge>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bool_array_from_cycle_ranges() {
        let ranges = [SerdeRange(2..=3), SerdeRange(7..=7), SerdeRange(20..=21)];
        let arr = matches_to_bool(&ranges, 10);
        let want: Vec<bool> = (0..10).map(|i| matches!(i, 2 | 3 | 7)).collect();
        assert_eq!(arr, want);
    }

    #[test]
    fn bool_array_clamps_past_end() {
        let ranges = [SerdeRange(8..=30)];
        let arr = matches_to_bool(&ranges, 10);
        assert!(arr[8]);
        assert!(arr[9]);
        assert_eq!(arr.iter().filter(|&&v| v).count(), 2);
    }
}
