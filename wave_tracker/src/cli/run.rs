use wellen::SignalRef;
use wellen::simple;

use crate::analysis::{
    bug_scan_pr_in_both, deadlock_analysis, deadlock_tail, dispatch_lsq_check,
    find_enq_rob_id_p_rd, find_p_rd_in_pipeline, find_rob_id_in_pipeline, flush_sync_scan,
    prf_iq_mismatch_scan, rob_iq_desync_scan, scan_time_range, timeline_trace, trace_p_rd_timeline,
    trace_pc_timeline, trace_rob_id_timeline, who_produces_pr,
};
use crate::core::{default_wave_path, find_time_idx_at_or_before, pc_hex_to_binary};

use super::Args;

pub fn run(args: Args) -> Result<(), Box<dyn std::error::Error>> {
    let path = args.file.unwrap_or_else(default_wave_path);
    if !path.exists() {
        return Err(format!("Waveform file not found: {}", path.display()).into());
    }

    let mut wf = simple::read(&path)?;
    let time_table_vec: Vec<wellen::Time> = wf.time_table().to_vec();
    let time_table = time_table_vec.as_slice();
    if time_table.is_empty() {
        return Err("Empty waveform: no time samples in file".into());
    }
    let hierarchy = wf.hierarchy();

    let timescale = hierarchy
        .timescale()
        .map(|ts| format!("{}{:?}", ts.factor, ts.unit))
        .unwrap_or_else(|| "?".into());

    let var_count = hierarchy.iter_vars().count();
    println!(
        "Loaded waveform: timescale {}, {} signals",
        timescale, var_count
    );

    if args.list_signals {
        for var in hierarchy.iter_vars() {
            let name = var.full_name(hierarchy);
            if args.grep.as_ref().is_none_or(|g| name.contains(g)) {
                println!("  {} -> {}", var.signal_ref().index(), name);
            }
        }
        return Ok(());
    }

    if let Some(t) = args.time {
        let time_idx = find_time_idx_at_or_before(time_table, t);
        if let Some(idx) = time_idx {
            let to_show: Vec<(String, SignalRef)> = hierarchy
                .iter_vars()
                .filter(|v| {
                    args.grep
                        .as_ref()
                        .is_none_or(|g| v.full_name(hierarchy).contains(g))
                })
                .map(|v| (v.full_name(hierarchy), v.signal_ref()))
                .collect();

            let to_load: Vec<SignalRef> = to_show.iter().map(|(_, sr)| *sr).collect();
            wf.load_signals(&to_load);

            println!("\nValues at time {} (idx {}):", t, idx);
            for (name, sig_ref) in &to_show {
                if let Some(sig) = wf.get_signal(*sig_ref)
                    && let Some(offset) = sig.get_offset(idx)
                {
                    let val = sig.get_value_at(&offset, 0);
                    println!("  {}: {}", name, val);
                }
            }
        } else {
            println!("No time <= {} in waveform", t);
        }
    } else if let Some(start) = args.bug_scan {
        let end = args.bug_scan_end.unwrap_or(start + 500);
        let sig_refs: Vec<(String, SignalRef)> = hierarchy
            .iter_vars()
            .filter_map(|v| {
                let name = v.full_name(hierarchy);
                let is_table_1 = name.contains("rmt.table_1")
                    && !(10..=19).any(|i| name.contains(&format!("table_{}", i)));
                if name.contains("freeList.buf_") || is_table_1 {
                    Some((name, v.signal_ref()))
                } else {
                    None
                }
            })
            .collect();
        bug_scan_pr_in_both(&mut wf, time_table, &sig_refs, start, end, &args.bug_pr)?;
    } else if let Some(start) = args.timeline {
        let end = args.timeline_end.unwrap_or(start + 150);
        timeline_trace(&mut wf, time_table, start, end)?;
    } else if let Some(start) = args.prf_iq_mismatch {
        let end = args.prf_iq_mismatch_end.unwrap_or(start + 5000);
        prf_iq_mismatch_scan(&mut wf, time_table, start, end)?;
    } else if let Some(start) = args.deadlock {
        let end = args.deadlock_end.unwrap_or(start + 5000);
        deadlock_analysis(&mut wf, time_table, start, end)?;
    } else if let Some(n) = args.deadlock_tail {
        deadlock_tail(&mut wf, time_table, n)?;
    } else if let Some(ref pr_bin) = args.who_produces {
        who_produces_pr(&mut wf, time_table, pr_bin)?;
    } else if let Some(ref pr_bin) = args.find_p_rd {
        find_p_rd_in_pipeline(&mut wf, time_table, pr_bin)?;
    } else if let Some(ref rid_bin) = args.find_rob_id {
        find_rob_id_in_pipeline(&mut wf, time_table, rid_bin)?;
    } else if let Some(ref rid_bin) = args.trace_rob_id {
        let start = args.trace_rob_id_start.unwrap_or(0);
        let end = args.trace_rob_id_end.or_else(|| time_table.last().copied());
        trace_rob_id_timeline(&mut wf, time_table, rid_bin, start, end)?;
    } else if let Some(ref pr_bin) = args.trace_p_rd {
        let start = args.trace_p_rd_start.unwrap_or(0);
        let end = args.trace_p_rd_end.or_else(|| time_table.last().copied());
        trace_p_rd_timeline(&mut wf, time_table, pr_bin, start, end)?;
    } else if let Some(ref s) = args.enq_match {
        let parts: Vec<&str> = s.split(',').collect();
        if parts.len() >= 2 {
            find_enq_rob_id_p_rd(&mut wf, time_table, parts[0].trim(), parts[1].trim())?;
        } else {
            eprintln!("--enq-match requires rob_id,p_rd (e.g. 0111,100101)");
        }
    } else if let Some(ref s) = args.dispatch_lsq {
        let parts: Vec<&str> = s.split(',').collect();
        if parts.len() >= 2 {
            let start = args.dispatch_lsq_start.unwrap_or(0);
            let end = args.dispatch_lsq_end.or_else(|| time_table.last().copied());
            dispatch_lsq_check(
                &mut wf,
                time_table,
                parts[0].trim(),
                parts[1].trim(),
                start,
                end,
            )?;
        } else {
            eprintln!("--dispatch-lsq requires rob_id,p_rd (e.g. 0111,100101)");
        }
    } else if let Some(ref hex_pc) = args.trace_pc {
        let pc_bin = pc_hex_to_binary(hex_pc)?;
        let last_t = time_table.last().copied().unwrap_or(0);
        let start = args
            .trace_pc_start
            .unwrap_or_else(|| last_t.saturating_sub(500));
        let end = args.trace_pc_end.unwrap_or(last_t);
        trace_pc_timeline(&mut wf, time_table, &pc_bin, hex_pc, start, end)?;
    } else if let Some(start) = args.rob_iq_desync {
        let end = args.rob_iq_desync_end.unwrap_or(start + 50000);
        rob_iq_desync_scan(&mut wf, time_table, start, end)?;
    } else if let Some(start) = args.flush_sync {
        let end = args.flush_sync_end.unwrap_or(start + 50000);
        flush_sync_scan(&mut wf, time_table, start, end)?;
    } else if let (Some(start), Some(g)) = (args.scan, &args.grep) {
        let end = args.scan_end.unwrap_or(start + 500);
        let to_show: Vec<(String, SignalRef)> = {
            let h = wf.hierarchy();
            h.iter_vars()
                .filter(|v| v.full_name(h).contains(g))
                .map(|v| (v.full_name(h), v.signal_ref()))
                .collect()
        };
        scan_time_range(
            &mut wf,
            &to_show,
            time_table,
            start,
            end,
            args.filter_value.as_deref(),
            args.filter_rd_index.as_deref(),
        );
    } else if let Some(ref g) = args.grep {
        for var in hierarchy.iter_vars() {
            let name = var.full_name(hierarchy);
            if name.contains(g) {
                println!("  {} -> {}", var.signal_ref().index(), name);
            }
        }
    }

    Ok(())
}
