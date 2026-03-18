//! Wave tracker: parse FST/VCD waveforms for nzea RTL debugging.
//! Uses wellen for direct FST parsing (no conversion).

use std::path::PathBuf;

use clap::Parser;
use wellen::simple;
use wellen::{SignalRef, Time, TimeTableIdx};

/// Default path: ../remu/target/trace.fst (nzea and remu are siblings under chip-dev)
fn default_path() -> PathBuf {
    let manifest = std::env::var("CARGO_MANIFEST_DIR").unwrap_or_else(|_| ".".into());
    let base = PathBuf::from(&manifest)
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(manifest.clone()));
    // wave_tracker -> nzea -> chip-dev; remu is sibling of nzea
    base.parent()
        .and_then(|p| p.parent())
        .map(|chip_dev| chip_dev.join("remu").join("target").join("trace.fst"))
        .unwrap_or_else(|| PathBuf::from("../remu/target/trace.fst"))
}

#[derive(Parser)]
#[command(name = "wave_tracker")]
#[command(about = "Load and inspect FST/VCD waveforms for nzea RTL debugging")]
struct Args {
    /// Path to waveform file
    #[arg(short, long)]
    file: Option<PathBuf>,

    /// List all signal names and exit
    #[arg(short, long)]
    list_signals: bool,

    /// Filter signals by name substring
    #[arg(short, long)]
    grep: Option<String>,

    /// Print signal values at this time (timescale units)
    #[arg(short, long)]
    time: Option<u64>,

    /// Start time filter (reserved)
    #[arg(short, long)]
    start: Option<u64>,

    /// End time filter (reserved)
    #[arg(short, long)]
    end: Option<u64>,

    /// Scan time range [start,end], print when signal matching grep changes (e.g. do_flush=1)
    #[arg(long)]
    scan: Option<u64>,

    /// With --scan: end time (default: start+500)
    #[arg(long)]
    scan_end: Option<u64>,

    /// With --scan: only print when any matching signal's value contains this string (e.g. "8000611" for next_pc ~0x80006118)
    #[arg(long)]
    filter_value: Option<String>,

    /// With --scan: only print when commit_bits_rd_index matches this 5-bit binary (e.g. "00010" for sp/x2)
    #[arg(long)]
    filter_rd_index: Option<String>,

    /// Bug scan: find cycles where PR is both in FreeList buf AND RMT(sp)=PR. PR in binary (e.g. "000010" for PR2)
    #[arg(long)]
    bug_scan: Option<u64>,

    /// With --bug-scan: end time (default: start+500)
    #[arg(long)]
    bug_scan_end: Option<u64>,

    /// With --bug-scan: PR value in 6-bit binary (default "000010" for PR2)
    #[arg(long, default_value = "000010")]
    bug_pr: String,

    /// Timeline trace: dump commits and enqs from start to end for t=108 bug analysis
    #[arg(long)]
    timeline: Option<u64>,

    /// With --timeline: end time (default: start+150)
    #[arg(long)]
    timeline_end: Option<u64>,

    /// PRF-IQ mismatch scan: find cycles where PRF has bank_ready=1 but IQ entry has rs1/rs2_ready=0
    #[arg(long)]
    prf_iq_mismatch: Option<u64>,

    /// With --prf-iq-mismatch: end time (default: start+5000)
    #[arg(long)]
    prf_iq_mismatch_end: Option<u64>,

    /// Deadlock analysis: find first PRF-IQ mismatch, then dump prf_write/bypass/iq state for t-25..t+2
    #[arg(long)]
    deadlock: Option<u64>,

    /// With --deadlock: end time (default: start+5000)
    #[arg(long)]
    deadlock_end: Option<u64>,

    /// Dump IQ state for last N cycles of trace to find all-blocked deadlock
    #[arg(long)]
    deadlock_tail: Option<u64>,

    /// Find who produces a given PR: scan for prf_write/commit with p_rd=PR
    #[arg(long)]
    who_produces: Option<String>,

    /// Find instruction with p_rd=PR in IQ/ROB/EXU/MemUnit (binary, e.g. 100101)
    #[arg(long)]
    find_p_rd: Option<String>,

    /// Find instruction with rob_id=X in EXU/MemUnit (binary, e.g. 00111 for slot 7)
    #[arg(long)]
    find_rob_id: Option<String>,

    /// Trace rob_id through full timeline: enq, IQ, issue, EXU, MemUnit (binary, e.g. 0111)
    #[arg(long)]
    trace_rob_id: Option<String>,

    /// With --trace-rob-id: start time (default 0)
    #[arg(long)]
    trace_rob_id_start: Option<u64>,

    /// With --trace-rob-id: end time (default: last)
    #[arg(long)]
    trace_rob_id_end: Option<u64>,

    /// Trace p_rd (producer) through timeline (binary, e.g. 100101 for PR37)
    #[arg(long)]
    trace_p_rd: Option<String>,

    /// With --trace-p-rd: start time (default 0)
    #[arg(long)]
    trace_p_rd_start: Option<u64>,

    /// With --trace-p-rd: end time (default: last)
    #[arg(long)]
    trace_p_rd_end: Option<u64>,

    /// Find when rob_id=X and p_rd=Y were enqueued together (binary, e.g. --enq-match 0111,100101)
    #[arg(long)]
    enq_match: Option<String>,

    /// Check LSQ dispatch sync: when rob_id,p_rd dispatched, did ROB+IQ+LSQ all fire? (e.g. 0111,100101)
    #[arg(long)]
    dispatch_lsq: Option<String>,

    /// With --dispatch-lsq: start time (default 0)
    #[arg(long)]
    dispatch_lsq_start: Option<u64>,

    /// With --dispatch-lsq: end time (default: last)
    #[arg(long)]
    dispatch_lsq_end: Option<u64>,

    /// Trace instruction by PC (hex, e.g. 80005c9c) through IQ/issue/BRU
    #[arg(long)]
    trace_pc: Option<String>,

    /// With --trace-pc: start time (default: last-500)
    #[arg(long)]
    trace_pc_start: Option<u64>,

    /// With --trace-pc: end time (default: last)
    #[arg(long)]
    trace_pc_end: Option<u64>,

    /// Scan for ROB-IQ desync: IQ full but rob_enq fired (instruction entered ROB but not IQ)
    #[arg(long)]
    rob_iq_desync: Option<u64>,

    /// With --rob-iq-desync: end time (default: start+50000)
    #[arg(long)]
    rob_iq_desync_end: Option<u64>,

    /// Scan for flush timing: when do_flush/iq.flush change, check ROB vs IQ sync
    #[arg(long)]
    flush_sync: Option<u64>,

    /// With --flush-sync: end time (default: start+50000)
    #[arg(long)]
    flush_sync_end: Option<u64>,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    let path = args.file.unwrap_or_else(default_path);
    if !path.exists() {
        return Err(format!("Waveform file not found: {}", path.display()).into());
    }

    let mut wf = simple::read(&path)?;
    let time_table_vec: Vec<Time> = wf.time_table().to_vec();
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
            if args.grep.as_ref().map_or(true, |g| name.contains(g)) {
                println!("  {} -> {}", var.signal_ref().index(), name);
            }
        }
        return Ok(());
    }

    if let Some(t) = args.time {
        // Find time_table_idx: largest i where time_table[i] <= t
        let time_idx = find_time_idx_at_or_before(time_table, t);
        if let Some(idx) = time_idx {
            // Collect (name, signal_ref) before load_signals (avoids borrow conflict)
            let to_show: Vec<(String, SignalRef)> = hierarchy
                .iter_vars()
                .filter(|v| {
                    args.grep
                        .as_ref()
                        .map_or(true, |g| v.full_name(hierarchy).contains(g))
                })
                .map(|v| (v.full_name(hierarchy), v.signal_ref()))
                .collect();

            let to_load: Vec<SignalRef> = to_show.iter().map(|(_, sr)| *sr).collect();
            wf.load_signals(&to_load);

            println!("\nValues at time {} (idx {}):", t, idx);
            for (name, sig_ref) in &to_show {
                if let Some(sig) = wf.get_signal(*sig_ref) {
                    if let Some(offset) = sig.get_offset(idx) {
                        let val = sig.get_value_at(&offset, 0);
                        println!("  {}: {}", name, val);
                    }
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
            dispatch_lsq_check(&mut wf, time_table, parts[0].trim(), parts[1].trim(), start, end)?;
        } else {
            eprintln!("--dispatch-lsq requires rob_id,p_rd (e.g. 0111,100101)");
        }
    } else if let Some(ref hex_pc) = args.trace_pc {
        let pc_bin = pc_hex_to_binary(hex_pc)?;
        let last_t = time_table.last().copied().unwrap_or(0);
        let start = args.trace_pc_start.unwrap_or_else(|| last_t.saturating_sub(500));
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

/// Scan time range, print (time, value) when any matching signal changes.
/// If filter_value is Some, only print when any signal's value string contains the filter.
/// If filter_rd_index is Some, only print when a signal named *rd_index* has value matching the filter.
fn scan_time_range(
    wf: &mut wellen::simple::Waveform,
    to_show: &[(String, SignalRef)],
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
    filter_value: Option<&str>,
    filter_rd_index: Option<&str>,
) {
    if to_show.is_empty() {
        return;
    }

    let to_load: Vec<SignalRef> = to_show.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let mut last_vals: Vec<String> = vec!["?".into(); to_show.len()];
    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;
        let mut changed = false;
        let mut vals = Vec::with_capacity(to_show.len());
        for (j, (_name, sig_ref)) in to_show.iter().enumerate() {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    if last_vals.get(j) != Some(&val) {
                        changed = true;
                    }
                    vals.push(val);
                }
            }
        }
        if changed {
            let matches_value = filter_value.map_or(true, |fv| {
                vals.iter().any(|v| {
                    v.contains(fv)
                        || (v.chars().all(|c| c == '0' || c == '1' || c == 'x' || c == 'z')
                            && binary_to_hex_contains(v, fv))
                })
            });
            let matches_rd_index = filter_rd_index.map_or(true, |fri| {
                to_show.iter().zip(vals.iter()).any(|((name, _), v)| {
                    name.contains("commit_bits_rd_index") && v == fri
                })
            });
            if matches_value && matches_rd_index {
                println!("t={} idx={}", t, idx);
                for (j, (name, _)) in to_show.iter().enumerate() {
                    if let Some(v) = vals.get(j) {
                        println!("  {}: {}", name, v);
                    }
                }
            }
            for (j, v) in vals.iter().enumerate() {
                if let Some(lv) = last_vals.get_mut(j) {
                    *lv = v.clone();
                }
            }
        }
    }
}

/// Check if a binary string (e.g. "10000000000000000110000100011000") when converted to hex
/// contains the filter substring (e.g. "8000611" matches 0x80006118).
fn binary_to_hex_contains(bin: &str, filter: &str) -> bool {
    let clean: String = bin.chars().filter(|c| *c == '0' || *c == '1').collect();
    if clean.is_empty() || clean.len() > 128 {
        return false;
    }
    let n = match u128::from_str_radix(&clean, 2) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let hex_lower = format!("{:x}", n);
    let hex_upper = format!("{:X}", n);
    hex_lower.contains(filter) || hex_upper.contains(filter)
}

/// Bug scan: find cycles where PR is both in FreeList (any buf slot) AND RMT table_1 (sp) = PR.
fn bug_scan_pr_in_both(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    sig_refs: &[(String, SignalRef)],
    start: wellen::Time,
    end: wellen::Time,
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    if sig_refs.is_empty() {
        return Err("Could not find freeList.buf_* or rmt.table_1 signals".into());
    }
    if !sig_refs.iter().any(|(n, _)| n.contains("table_1")) {
        return Err("Could not find rmt.table_1".into());
    }

    let to_load: Vec<SignalRef> = sig_refs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    println!("Bug scan: PR={} in FreeList buf AND RMT(sp)=table_1", pr_bin);
    println!("Cycles where both true:\n");

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;

        let mut table_1_val = String::new();
        let mut pr_in_buf = false;
        let mut buf_slots_with_pr: Vec<String> = Vec::new();

        for (name, sig_ref) in sig_refs.iter() {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    let val_trim = val.trim();
                    if name.contains("table_1") {
                        table_1_val = val_trim.to_string();
                    } else if val_trim == pr_bin || val_trim.ends_with(pr_bin) {
                        pr_in_buf = true;
                        buf_slots_with_pr.push(name.clone());
                    }
                }
            }
        }

        let rmt_has_pr = table_1_val == pr_bin;

        if pr_in_buf && rmt_has_pr {
            println!("t={} idx={} *** BUG: PR{} in FreeList AND table_1(sp)=PR{}", t, idx, pr_bin, pr_bin);
            println!("  rmt.table_1: {}", table_1_val);
            for slot in &buf_slots_with_pr {
                println!("  {}: {} (contains PR)", slot, pr_bin);
            }
        }
    }

    Ok(())
}

/// Timeline trace: dump commits and enqs with ROB head/tail, FreeList, RMT for bug analysis.
fn timeline_trace(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let sig_names = [
        "rob.io_commit_valid",
        "rob.io_commit_bits_rd_index",
        "rob.io_commit_bits_p_rd",
        "rob.io_commit_bits_old_p_rd",
        "rob.enq_req_valid",
        "rob.enq_req_ready",
        "rob.enq_req_bits_rd_index",
        "rob.enq_req_bits_p_rd",
        "rob.enq_req_bits_old_p_rd",
        "rob.enq_rob_id",
        "rob.head_ptr",
        "rob.tail_ptr",
        "rob.do_flush",
        "idu.freeList.head",
        "idu.freeList.tail",
        "idu.freeList.buf_0",
        "idu.freeList.buf_1",
        "idu.freeList.buf_2",
        "idu.freeList.buf_3",
        "idu.freeList.buf_4",
        "idu.freeList.buf_5",
        "idu.freeList.buf_6",
        "idu.freeList.buf_7",
        "idu.freeList.buf_8",
        "idu.freeList.buf_9",
        "idu.freeList.buf_10",
        "idu.rmt.table_1",
    ];

    let to_show: Vec<(String, SignalRef)> = hierarchy
        .iter_vars()
        .filter_map(|v| {
            let name = v.full_name(hierarchy);
            if name.contains("core.rob") || name.contains("core.idu.freeList") || name.contains("core.idu.rmt") {
                if sig_names.iter().any(|s| name.contains(s)) {
                    Some((name, v.signal_ref()))
                } else {
                    None
                }
            } else {
                None
            }
        })
        .collect();

    let to_load: Vec<SignalRef> = to_show.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name))
            .map(|(_, v)| v.clone())
    };

    let mut commits: Vec<(u64, String, String, String)> = Vec::new();
    let mut enqs: Vec<(u64, String, String, String, String)> = Vec::new();
    let mut flushes: Vec<u64> = Vec::new();

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &to_show {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let do_flush = get_val("do_flush", &vals).map_or(false, |v| v == "1");
        if do_flush {
            flushes.push(t);
        }

        let commit_valid = get_val("io_commit_valid", &vals).map_or(false, |v| v == "1");
        if commit_valid {
            let rd = get_val("commit_bits_rd_index", &vals).unwrap_or_else(|| "?".to_string());
            let p_rd = get_val("commit_bits_p_rd", &vals).unwrap_or_else(|| "?".to_string());
            let old = get_val("commit_bits_old_p_rd", &vals).unwrap_or_else(|| "?".to_string());
            commits.push((t, rd.to_string(), p_rd.to_string(), old.to_string()));
        }

        let enq_valid = get_val("enq_req_valid", &vals).map_or(false, |v| v == "1");
        let enq_ready = get_val("enq_req_ready", &vals).map_or(false, |v| v == "1");
        if enq_valid && enq_ready {
            let rd = get_val("enq_req_bits_rd_index", &vals).unwrap_or_else(|| "?".to_string());
            let p_rd = get_val("enq_req_bits_p_rd", &vals).unwrap_or_else(|| "?".to_string());
            let old = get_val("enq_req_bits_old_p_rd", &vals).unwrap_or_else(|| "?".to_string());
            let rob_id = get_val("enq_rob_id", &vals).unwrap_or_else(|| "?".to_string());
            enqs.push((t, rd.to_string(), p_rd.to_string(), old.to_string(), rob_id.to_string()));
        }
    }

    println!("\n=== TIMELINE TRACE t={}..{} ===\n", start, end);

    println!("## Flushes");
    for &t in &flushes {
        println!("  t={}", t);
    }

    println!("\n## Commits (in-order, head commits each cycle)");
    println!("  t     | rd_index | p_rd | old_p_rd | Effect");
    println!("  ------|----------|------|----------|-------");
    for (t, rd, p_rd, old) in &commits {
        let effect = if old == p_rd {
            "reuse (no push)"
        } else {
            "push old"
        };
        println!("  {:5} | {} | {} | {} | {}", t, rd, p_rd, old, effect);
    }

    println!("\n## Dispatches (enq)");
    println!("  t     | rob_id | rd_index | p_rd | old_p_rd");
    println!("  ------|--------|----------|------|--------");
    for (t, rd, p_rd, old, rob_id) in &enqs {
        println!("  {:5} | {} | {} | {} | {}", t, rob_id, rd, p_rd, old);
    }

    // Find instruction that commits at t=108
    println!("\n## Instruction that commits at t=108");
    let commit_108 = commits.iter().find(|(t, _, _, _)| *t == 108);
    if let Some((_, rd, p_rd, old)) = commit_108 {
        println!("  rd_index={}, p_rd={}, old_p_rd={}", rd, p_rd, old);
        println!("  ROB head at t=108 commits this instruction.");

        // Get head_ptr at t=108 to know which slot
        let idx_108 = time_table.iter().position(|&ti| ti == 108).map(|i| i as u32);
        if let Some(idx) = idx_108 {
            if idx < time_table.len() as u32 {
                let mut head_val = String::new();
                let mut tail_val = String::new();
                for (name, sig_ref) in &to_show {
                    if let Some(sig) = wf.get_signal(*sig_ref) {
                        if let Some(offset) = sig.get_offset(idx) {
                            let val = sig.get_value_at(&offset, 0).to_string();
                            if name.contains("rob.head_ptr") && !name.contains("mem") {
                                head_val = val;
                            } else if name.contains("rob.tail_ptr") && !name.contains("mem") {
                                tail_val = val;
                            }
                        }
                    }
                }
                println!("  ROB head_ptr at t=108: {} (slot index)", head_val);
                println!("  ROB tail_ptr at t=108: {}", tail_val);

                // Find when slot (head) was enqueued: enq when rob_id == head_ptr AND matches commit's rd/p_rd/old_p_rd
                let head_bin = head_val.trim();
                let last_flush_before_108 = flushes.iter().filter(|&&f| f < 108).last().copied().unwrap_or(0);
                let head_num = u32::from_str_radix(head_bin, 2).unwrap_or(0);
                let enq_for_slot: Vec<_> = enqs
                    .iter()
                    .filter(|(t, rd_e, p_e, old_e, rid)| {
                        *t > last_flush_before_108
                            && u32::from_str_radix(rid.trim(), 2).unwrap_or(99) == head_num
                            && rd_e == rd && p_e == p_rd && old_e == old
                    })
                    .collect();
                println!("\n  Enqueues into slot {} (rob_id={}) matching commit rd={} p_rd={} old_p_rd={}:", head_bin, head_bin, rd, p_rd, old);
                for (t, rd, p_rd, old, _) in &enq_for_slot {
                    println!("    t={}: rd_index={}, p_rd={}, old_p_rd={}", t, rd, p_rd, old);
                }
                if let Some((t_disp, rd_d, p_d, old_d)) = enq_for_slot.first().map(|(t, rd, p, o, _)| (*t, rd.clone(), p.clone(), o.clone())) {
                println!("\n  *** DISPATCH CYCLE for t=108 commit: t={} ***", t_disp);
                println!("  At dispatch: rd_index={}, p_rd={}, old_p_rd={}", rd_d, p_d, old_d);

                // Get FreeList and RMT at dispatch cycle
                let idx_disp = time_table.iter().position(|&ti| ti == t_disp).map(|i| i as u32);
                if let Some(idx_d) = idx_disp {
                    if idx_d < time_table.len() as u32 {
                        let mut fl_head = String::new();
                        let mut fl_tail = String::new();
                        let mut buf_head_val = String::new();
                        let mut table_1 = String::new();
                        for (name, sig_ref) in &to_show {
                            if let Some(sig) = wf.get_signal(*sig_ref) {
                                if let Some(offset) = sig.get_offset(idx_d) {
                                    let val = sig.get_value_at(&offset, 0).to_string();
                                    if name.ends_with("freeList.head") {
                                        fl_head = val;
                                    } else if name.ends_with("freeList.tail") {
                                        fl_tail = val;
                                    } else if name.ends_with("table_1") && name.contains("rmt") {
                                        table_1 = val;
                                    }
                                }
                            }
                        }
                        // buf[head] - head is index. Parse head as decimal
                        let head_idx: usize = u32::from_str_radix(fl_head.trim(), 2).unwrap_or(0) as usize;
                        for (name, sig_ref) in &to_show {
                            if name.contains(&format!("freeList.buf_{}", head_idx)) {
                                if let Some(sig) = wf.get_signal(*sig_ref) {
                                    if let Some(offset) = sig.get_offset(idx_d) {
                                        buf_head_val = sig.get_value_at(&offset, 0).to_string();
                                    }
                                }
                                break;
                            }
                        }
                        if buf_head_val.is_empty() && head_idx <= 10 {
                            for (name, sig_ref) in &to_show {
                                if name.contains(&format!("buf_{}", head_idx)) && name.contains("freeList") {
                                    if let Some(sig) = wf.get_signal(*sig_ref) {
                                        if let Some(offset) = sig.get_offset(idx_d) {
                                            buf_head_val = sig.get_value_at(&offset, 0).to_string();
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        println!("  FreeList at t={}: head={}, tail={}, buf(head)={}", t_disp, fl_head, fl_tail, buf_head_val);
                        println!("  RMT table_1(sp) at t={}: {}", t_disp, table_1);
                    }
                }
                }
            }
        }
    }

    // Last commit before t=108 instruction's dispatch
    println!("\n## Commits between t=10 and t=108 (in dispatch order)");
    let commits_10_108: Vec<_> = commits.iter().filter(|(t, _, _, _)| *t >= 10 && *t <= 108).collect();
    for (t, rd, p_rd, old) in &commits_10_108 {
        let effect = if old == p_rd { "reuse" } else { "push" };
        println!("  t={}: rd={}, p_rd={}, old_p_rd={} {}", t, rd, p_rd, old, effect);
    }

    Ok(())
}

/// PRF-IQ mismatch scan: find cycles where PRF has bank_ready=1 but IQ entry has rs1/rs2_ready=0.
/// Anomaly = PRF says ready, IQ says not ready (bypass persist should have updated IQ).
fn prf_iq_mismatch_scan(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("core.iq.valids_")
            || name.contains("core.iq.entries_") && (name.contains("p_rs1") || name.contains("p_rs2") || name.contains("rs1_ready") || name.contains("rs2_ready"))
            || name.contains("core.isu.bank_ready_")
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let mut found = 0u32;
    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals)
                .map_or(false, |v| v == "1");
            if !valid {
                continue;
            }
            let p_rs1 = get_val(&format!("iq.entries_{}_p_rs1", entry), &vals);
            let p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
            let rs1_ready = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            let rs2_ready = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                .map_or(false, |v| v == "1");

            for (src, p_rs, iq_ready) in [
                ("rs1", p_rs1, rs1_ready),
                ("rs2", p_rs2, rs2_ready),
            ] {
                let Some(pr_bin) = p_rs else { continue };
                let pr = u32::from_str_radix(pr_bin.trim(), 2).unwrap_or(99);
                if pr == 0 {
                    continue;
                }
                if iq_ready {
                    continue;
                }
                let bank = (pr >> 4) as usize;
                let idx_bank = (pr & 0xF) as usize;
                let bank_suffix = format!("bank_ready_{}_{}", bank, idx_bank);
                let prf_ready = get_val(&bank_suffix, &vals).map_or(false, |v| v == "1");
                if prf_ready {
                    found += 1;
                    println!(
                        "t={} *** PRF-IQ MISMATCH: entry={} {} p_rs={} (PR{}) PRF ready=1 IQ ready=0",
                        t, entry, src, pr_bin, pr
                    );
                }
            }
        }
    }
    println!("\nTotal PRF-IQ mismatches found: {}", found);
    Ok(())
}

/// Deadlock analysis: find first PRF-IQ mismatch, then dump prf_write/bypass/IQ state around it.
fn deadlock_analysis(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("core.iq.valids_")
            || (name.contains("core.iq.entries_") && (name.contains("p_rs1") || name.contains("p_rs2") || name.contains("rs1_ready") || name.contains("rs2_ready")))
            || name.contains("core.isu.bank_ready_")
            || (name.contains("core.iq.io_prf_write_") && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("core.iq.io_bypass_level1_") && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("core.iq.io_in_") && (name.contains("valid") || name.contains("ready") || name.contains("bits_p_rs1") || name.contains("bits_p_rs2") || name.contains("bits_rs1_ready") || name.contains("bits_rs2_ready")))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    const PR2_BIN: &str = "000010";
    let mut first_mismatch: Option<(wellen::Time, usize, &str)> = None;
    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }
        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
            if !valid {
                continue;
            }
            let p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
            let rs2_ready = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
            if let Some(pr_bin) = &p_rs2 {
                if pr_bin.trim() == PR2_BIN && !rs2_ready {
                    let bank = 0;
                    let idx_bank = 2;
                    let bank_suffix = format!("bank_ready_{}_{}", bank, idx_bank);
                    let prf_ready = get_val(&bank_suffix, &vals).map_or(false, |v| v == "1");
                    if prf_ready {
                        first_mismatch = Some((t, entry, "rs2"));
                        break;
                    }
                }
            }
        }
        if first_mismatch.is_some() {
            break;
        }
    }

    let Some((t_mismatch, entry, src)) = first_mismatch else {
        println!("No PRF-IQ mismatch found in range.");
        return Ok(());
    };

    println!("First PRF-IQ mismatch at t={}, entry={}, {} (PR2/sp)", t_mismatch, entry, src);
    println!("\n--- Timeline t-25 to t+2 (prf_write, bypass, IQ entry {}, in_fire) ---\n", entry);

    let t_start = t_mismatch.saturating_sub(25);
    let t_end = t_mismatch + 2;

    for (i, &t) in time_table.iter().enumerate() {
        if t < t_start || t > t_end {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let mut prf_writes: Vec<String> = Vec::new();
        for port in 0..6 {
            let v = get_val(&format!("iq.io_prf_write_{}_valid", port), &vals);
            let a = get_val(&format!("iq.io_prf_write_{}_bits_addr", port), &vals);
            if v.as_deref() == Some("1") {
                prf_writes.push(format!("P{}:{}", port, a.as_deref().unwrap_or("?")));
            }
        }
        let mut bypass: Vec<String> = Vec::new();
        for port in 0..6 {
            let v = get_val(&format!("iq.io_bypass_level1_{}_valid", port), &vals);
            let a = get_val(&format!("iq.io_bypass_level1_{}_bits_addr", port), &vals);
            if v.as_deref() == Some("1") {
                bypass.push(format!("B{}:{}", port, a.as_deref().unwrap_or("?")));
            }
        }
        let in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let in_ready = get_val("iq.io_in_ready", &vals).map_or(false, |v| v == "1");
        let in_fire = in_valid && in_ready;
        let in_p_rs2 = get_val("iq.io_in_bits_p_rs2", &vals)
            .or_else(|| get_val("iq.io_in_bits_r_p_rs2", &vals));

        let e_valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
        let e_p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
        let e_rs2_ready = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
        let bank_2 = get_val("bank_ready_0_2", &vals).map_or(false, |v| v == "1");

        let prf_w = if prf_writes.is_empty() {
            ".".to_string()
        } else {
            prf_writes.join(" ")
        };
        let byp = if bypass.is_empty() {
            ".".to_string()
        } else {
            bypass.join(" ")
        };
        let mark = if t == t_mismatch { " <-- MISMATCH" } else { "" };
        println!(
            "t={:4} prf_write=[{}] bypass=[{}] in_fire={} in_p_rs2={:?} bank_2={} entry{}: valid={} p_rs2={:?} rs2_ready={}{}",
            t,
            prf_w,
            byp,
            in_fire,
            in_p_rs2,
            bank_2,
            entry,
            e_valid,
            e_p_rs2,
            e_rs2_ready,
            mark
        );
    }
    Ok(())
}

/// Dump IQ state for last N cycles to find all-blocked deadlock.
fn deadlock_tail(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    n: u64,
) -> Result<(), Box<dyn std::error::Error>> {
    if time_table.is_empty() {
        println!("Empty waveform.");
        return Ok(());
    }
    let total = time_table.len();
    let start_idx = if total as u64 > n { total - n as usize } else { 0 };
    let t_start = time_table[start_idx];

    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("core.iq.valids_")
            || (name.contains("core.iq.entries_") && (name.contains("fu_type") || name.contains("p_rs1") || name.contains("p_rs2") || name.contains("p_rd") || name.contains("rs1_ready") || name.contains("rs2_ready") || name.contains("rob_id")))
            || (name.contains("core.iq.count") || name.contains("core.iq.full"))
            || (name.contains("core.iq.io_in_valid") || name.contains("core.iq.io_in_ready"))
            || (name.contains("core.iq.io_issuePorts") && name.contains("ready"))
            || name.contains("core.isu.bank_ready_")
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    const FU_NAMES: &[&str] = &["ALU", "BRU", "AGU", "MUL", "DIV", "SYSU"];
    let fu_name = |v: Option<String>| -> String {
        let s = v.as_deref().unwrap_or("?");
        if let Ok(u) = u32::from_str_radix(s.trim(), 2) {
            FU_NAMES.get(u as usize).copied().unwrap_or("?").to_string()
        } else {
            s.to_string()
        }
    };

    println!("Last {} cycles (t={} to t={})\n", n, t_start, time_table.last().copied().unwrap_or(0));
    let mut all_blocked_at: Option<wellen::Time> = None;

    for (i, &t) in time_table.iter().enumerate() {
        if i < start_idx {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let count = get_val("iq.count", &vals).and_then(|s| u32::from_str_radix(s.trim(), 2).ok()).unwrap_or(99);
        let full = get_val("iq.full", &vals).map_or(false, |v| v == "1");
        let in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let in_ready = get_val("iq.io_in_ready", &vals).map_or(false, |v| v == "1");

        let mut entries_blocked = Vec::new();
        let mut any_can_issue = false;
        let mut num_valid = 0u32;
        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
            if valid {
                num_valid += 1;
            }
            if !valid {
                continue;
            }
            let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals).map_or(false, |v| v == "1");
            let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
            let can = r1 && r2;
            if can {
                any_can_issue = true;
            } else {
                entries_blocked.push((entry, !r1, !r2));
            }
        }
        // When count>0 but valids=0 (RTL bug: count/valids desync), we cannot issue (no valid slot).
        // Use entries data to show what *would* be blocked if they were valid.
        let valids_desync = count > 0 && num_valid == 0;
        if valids_desync {
            for entry in 0..8 {
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals).map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
                let can = r1 && r2;
                if can {
                    any_can_issue = true; // operands ready, but RTL can't issue (valids=0)
                } else {
                    entries_blocked.push((entry, !r1, !r2));
                }
            }
        }

        let blocked = count > 0 && (!any_can_issue || valids_desync);
        if blocked {
            all_blocked_at = Some(t);
        }

        let mark = if blocked { " *** ALL BLOCKED" } else { "" };
        let desync_note = if valids_desync { " [count/valids DESYNC - using entries]" } else { "" };
        println!(
            "t={:5} count={} full={} in_v={} in_r={}{}{}",
            t, count, full, in_valid, in_ready, mark, desync_note
        );
        for (entry, r1_miss, r2_miss) in &entries_blocked {
            let ft = get_val(&format!("iq.entries_{}_fu_type", entry), &vals);
            let rob_id = get_val(&format!("iq.entries_{}_rob_id", entry), &vals);
            let p1 = get_val(&format!("iq.entries_{}_p_rs1", entry), &vals);
            let p2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
            let p_rd = get_val(&format!("iq.entries_{}_p_rd", entry), &vals);
            let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals).map_or(false, |v| v == "1");
            let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
            let pr1_ready = p1.as_ref().and_then(|s| u32::from_str_radix(s.trim(), 2).ok()).map(|pr| {
                let (b, i) = ((pr >> 4) as usize, (pr & 0xF) as usize);
                get_val(&format!("bank_ready_{}_{}", b, i), &vals).map_or(false, |v| v == "1")
            }).unwrap_or(true);
            let pr2_ready = p2.as_ref().and_then(|s| u32::from_str_radix(s.trim(), 2).ok()).map(|pr| {
                if pr == 0 { true } else {
                    let (b, i) = ((pr >> 4) as usize, (pr & 0xF) as usize);
                    get_val(&format!("bank_ready_{}_{}", b, i), &vals).map_or(false, |v| v == "1")
                }
            }).unwrap_or(true);
            let rob_str = rob_id.as_deref().map(|s| format!(" rob_id={}", s.trim())).unwrap_or_default();
            let why = match (*r1_miss, *r2_miss) {
                (true, true) => "rs1,rs2",
                (true, false) => "rs1",
                (false, true) => "rs2",
                _ => "?",
            };
            let mismatch = (*r1_miss && pr1_ready) || (*r2_miss && pr2_ready);
            let mm = if mismatch { " (PRF-IQ MISMATCH!)" } else { "" };
            println!(
                "    entry{}: fu={}{} p_rd={:?} p_rs1={:?} p_rs2={:?} r1={} r2={} prf_r1={} prf_r2={} blocked_by={}{}",
                entry, fu_name(ft), rob_str, p_rd, p1, p2, r1, r2, pr1_ready, pr2_ready, why, mm
            );
        }
    }
    if let Some(t) = all_blocked_at {
        println!("\n*** Deadlock: all IQ entries blocked from t={}", t);
    }
    Ok(())
}

/// Find cycles where prf_write or commit has p_rd matching the given PR (binary, e.g. "100101").
fn who_produces_pr(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.io_prf_write_") && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("commit.io_rob_commit") && (name.contains("valid") || name.contains("p_rd")))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let pr_trim = pr_bin.trim();
    println!("Scanning for PR {} (produces/writes):\n", pr_trim);

    let last_t = time_table.last().copied().unwrap_or(0);
    let start = last_t.saturating_sub(2000);

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        for port in 0..6 {
            let v = get_val(&format!("iq.io_prf_write_{}_valid", port), &vals);
            let a = get_val(&format!("iq.io_prf_write_{}_bits_addr", port), &vals);
            if v.as_deref() == Some("1") && a.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!("t={} prf_write port {} -> PR {}", t, port, pr_trim);
            }
        }

        let cv = get_val("commit.io_rob_commit_valid", &vals).map_or(false, |v| v == "1");
        let cp = get_val("commit.io_rob_commit_bits_p_rd", &vals).as_deref().map(|s| s.trim().to_string());
        if cv && cp.as_deref() == Some(pr_trim) {
            let rd = get_val("commit.io_rob_commit_bits_rd_index", &vals);
            println!("t={} COMMIT rd_index={:?} p_rd={} -> PR {}", t, rd, pr_trim, pr_trim);
        }
    }
    Ok(())
}

/// Find instruction with p_rd=PR in IQ, ROB, EXU pipeline, MemUnit, issue ports.
fn find_p_rd_in_pipeline(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.entries_") && (name.contains("p_rd") || name.contains("valids_") || name.contains("rs1_ready") || name.contains("rs2_ready") || name.contains("fu_type") || name.contains("pc")))
            || (name.contains("rob.slots_p_rd_") || name.contains("rob.slots_is_done_") || name.contains("rob.slots_mem_type_") || name.contains("rob.slots_rd_index_") || name.contains("rob.head_ptr") || name.contains("rob.tail_ptr"))
            || (name.contains("exu.pipeOut_bits") && name.contains("p_rd"))
            || (name.contains("exu.io_issuePorts_") && (name.contains("valid") || name.contains("p_rd")))
            || (name.contains("memUnit.ls_slots_") && (name.contains("p_rd") || name.contains("valid")))
            || (name.contains("isu.io_out_bits_p_rd") || name.contains("isu.io_out_valid"))
            || (name.contains("iq.io_issuePorts_") && (name.contains("valid") || name.contains("p_rd")))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let pr_trim = pr_bin.trim();
    let last_t = time_table.last().copied().unwrap_or(0);
    let start = last_t.saturating_sub(200);

    println!("Finding instruction with p_rd={} (PR{}) in pipeline, t={}..{}\n", pr_trim, u32::from_str_radix(pr_trim, 2).unwrap_or(99), start, last_t);

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        for entry in 0..8 {
            let v = get_val(&format!("iq.entries_{}_p_rd", entry), &vals);
            if v.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals).map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
                let pc = get_val(&format!("iq.entries_{}_pc", entry), &vals);
                let ft = get_val(&format!("iq.entries_{}_fu_type", entry), &vals);
                println!("t={} IQ entry{}: p_rd={} valid={} r1={} r2={} pc={:?} fu={:?}", t, entry, pr_trim, valid, r1, r2, pc, ft);
            }
        }

        for slot in 0..16 {
            let v = get_val(&format!("rob.slots_p_rd_{}", slot), &vals);
            if v.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                let done = get_val(&format!("rob.slots_is_done_{}", slot), &vals).map_or(false, |v| v == "1");
                let head = get_val("rob.head_ptr", &vals);
                let tail = get_val("rob.tail_ptr", &vals);
                let mem_type = get_val(&format!("rob.slots_mem_type_{}", slot), &vals);
                let rd_idx = get_val(&format!("rob.slots_rd_index_{}", slot), &vals);
                println!("t={} ROB slot{}: p_rd={} is_done={} mem_type={:?} rd_index={:?} head={:?} tail={:?}", t, slot, pr_trim, done, mem_type, rd_idx, head, tail);
            }
        }

        for (port, name) in [("alu", "ALU"), ("bru", "BRU"), ("agu", "AGU"), ("mul", "MUL"), ("div", "DIV"), ("sysu", "SYSU")] {
            let v = get_val(&format!("iq.io_issuePorts_{}_valid", port), &vals).map_or(false, |x| x == "1");
            let p = get_val(&format!("iq.io_issuePorts_{}_bits_p_rd", port), &vals);
            if v && p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!("t={} ISSUE {}: p_rd={} (being issued)", t, name, pr_trim);
            }
        }

        let isu_valid = get_val("isu.io_out_valid", &vals).map_or(false, |v| v == "1");
        let isu_p = get_val("isu.io_out_bits_p_rd", &vals).or_else(|| get_val("isu.io_out_bits_r_p_rd", &vals));
        if isu_valid && isu_p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
            println!("t={} ISU out: p_rd={} (on way to IQ)", t, pr_trim);
        }

        for pipe in ["", "_1", "_3", "_4", "_5"] {
            let p = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
            if p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!("t={} EXU pipeOut{}: p_rd={} (in EX pipeline)", t, pipe, pr_trim);
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals).map_or(false, |x| x == "1");
            let p = get_val(&format!("memUnit.ls_slots_{}_p_rd", ls), &vals);
            if v && p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!("t={} MemUnit ls_slot{}: p_rd={} (load/store in flight)", t, ls, pr_trim);
            }
        }
    }
    Ok(())
}

/// Scan for ROB-IQ desync: IQ full but rob_enq fired (instruction entered ROB but not IQ).
fn rob_iq_desync_scan(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.count") || name.contains("iq.io_in_ready"))
            || (name.contains("rob.enq_req") && (name.contains("valid") || name.contains("ready")))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    println!("Scanning for ROB-IQ desync: IQ full but rob_enq fired, t={}..{}\n", start, end);

    let mut found = 0u32;
    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let iq_in_ready = get_val("iq.io_in_ready", &vals).map_or(false, |v| v == "1");
        let rob_enq_valid = get_val("rob.enq_req_valid", &vals).map_or(false, |v| v == "1");
        let rob_enq_ready = get_val("rob.enq_req_ready", &vals).map_or(false, |v| v == "1");
        let rob_enq_fire = rob_enq_valid && rob_enq_ready;
        let iq_count = get_val("iq.count", &vals);

        if !iq_in_ready && rob_enq_fire {
            found += 1;
            println!("t={} *** ROB-IQ DESYNC: IQ in_ready=0 but rob_enq fired (instruction entered ROB, not IQ) count={:?}", t, iq_count);
        }
    }
    println!("\nTotal ROB-IQ desync events found: {}", found);
    Ok(())
}

/// Scan for flush timing: when do_flush/iq.flush change, check ROB vs IQ sync.
fn flush_sync_scan(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob") && name.contains("do_flush")) || (name.contains("iq") && name.contains("flush"))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    println!("Scanning for flush timing, t={}..{}\n", start, end);

    let mut prev_rob_flush: Option<bool> = None;
    let mut prev_iq_flush: Option<bool> = None;

    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let rob_flush = get_val("rob.io_do_flush", &vals).or_else(|| get_val("do_flush", &vals)).map(|v| v == "1");
        let iq_flush = get_val("iq.io_flush", &vals).map(|v| v == "1");

        if let (Some(rf), Some(if_)) = (rob_flush, iq_flush) {
            if prev_rob_flush != Some(rf) || prev_iq_flush != Some(if_) {
                let sync = rf == if_;
                let mark = if !sync { " *** MISMATCH" } else { "" };
                println!("t={} rob_do_flush={} iq_flush={}{}", t, rf, if_, mark);
            }
            prev_rob_flush = Some(rf);
            prev_iq_flush = Some(if_);
        }
    }
    Ok(())
}

/// Parse hex PC (e.g. "80005c9c") to 32-bit binary string for waveform matching.
fn pc_hex_to_binary(hex: &str) -> Result<String, Box<dyn std::error::Error>> {
    let hex_clean = hex.trim_start_matches("0x").trim_start_matches("0X");
    let value = u32::from_str_radix(hex_clean, 16)
        .map_err(|e| format!("Invalid PC hex '{}': {}", hex, e))?;
    Ok(format!("{:032b}", value))
}

/// Trace instruction by PC through IQ/issue/BRU: find why it silently ends without entering BRU.
fn trace_pc_timeline(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    pc_bin: &str,
    hex_pc: &str,
    start: wellen::Time,
    end: wellen::Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.entries_") && (name.contains("pc") || name.contains("valids_") || name.contains("rs1_ready") || name.contains("rs2_ready") || name.contains("fu_type")))
            || (name.contains("iq.io_in") && (name.contains("valid") || name.contains("pc")))
            || (name.contains("io_issuePorts_") && (name.contains("valid") || name.contains("ready") || name.contains("bits_pc")))
            || (name.contains("iq.count") || name.contains("iq.full"))
            || (name.contains("iq.io_flush") || name.contains("flush"))
            || (name.contains("orderedPorts") && name.contains("ready"))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let matches_pc = |v: Option<&String>| v.as_deref().map(|s| s.trim()) == Some(pc_bin);

    // FuType: 0=ALU, 1=BRU, 2=LSU, 3=MUL, 4=DIV, 5=SYSU
    let fu_type_name = |ft: Option<&String>| -> String {
        ft.and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .map(|n| match n {
                0 => "ALU",
                1 => "BRU",
                2 => "LSU",
                3 => "MUL",
                4 => "DIV",
                5 => "SYSU",
                _ => "?",
            })
            .unwrap_or_else(|| "?")
            .to_string()
    };

    println!("Tracing PC=0x{} ({}) through IQ/BRU, t={}..{}\n", hex_pc, pc_bin, start, end);
    println!("{:>6} | {:12} | {:8} | {:4} {:4} {:4} | {:4} | {:12} {:12} | {:6} {:6}", "t", "IQ_in", "IQ_ent", "valid", "rs1", "rs2", "fu", "ISSUE", "BRU", "count", "flush");
    println!("{}", "-".repeat(100));

    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let iq_in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_in_pc = get_val("iq.io_in_bits_pc", &vals);
        let iq_in = iq_in_valid && matches_pc(iq_in_pc.as_ref());

        let mut iq_ent: Option<(usize, bool, bool, bool, String)> = None;
        for entry in 0..8 {
            let epc = get_val(&format!("iq.entries_{}_pc", entry), &vals);
            if matches_pc(epc.as_ref()) {
                let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals).map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
                let ft = get_val(&format!("iq.entries_{}_fu_type", entry), &vals);
                let ft_str = fu_type_name(ft.as_ref());
                if valid {
                    iq_ent = Some((entry, valid, r1, r2, ft_str));
                    break;
                }
                if iq_ent.is_none() {
                    iq_ent = Some((entry, valid, r1, r2, ft_str));
                }
            }
        }

        let mut issue_port = None;
        for (port, name) in [("alu", "ALU"), ("bru", "BRU"), ("agu", "AGU"), ("mul", "MUL"), ("div", "DIV"), ("sysu", "SYSU")] {
            let v = get_val(&format!("io_issuePorts_{}_valid", port), &vals).map_or(false, |x| x == "1");
            let pc = get_val(&format!("io_issuePorts_{}_bits_pc", port), &vals);
            if v && matches_pc(pc.as_ref()) {
                issue_port = Some(name.to_string());
                break;
            }
        }

        let bru_valid = get_val("io_issuePorts_bru_valid", &vals).map_or(false, |v| v == "1");
        let bru_ready = get_val("io_issuePorts_bru_ready", &vals).map_or(false, |v| v == "1");
        let bru_pc = get_val("io_issuePorts_bru_bits_pc", &vals);
        let bru_has_pc = bru_valid && matches_pc(bru_pc.as_ref());

        let count = get_val("iq.count", &vals).as_deref().map(|s| s.trim().to_string()).unwrap_or_else(|| "?".into());
        let flush = get_val("iq.io_flush", &vals).map_or(false, |v| v == "1");

        let iq_in_str = if iq_in { "IN" } else { "-" };
        let (ent_str, valid_str, r1_str, r2_str, ft_str) = match &iq_ent {
            Some((e, v, r1, r2, ft)) => (
                format!("[{}]", e),
                if *v { "1" } else { "0" },
                if *r1 { "1" } else { "0" },
                if *r2 { "1" } else { "0" },
                ft.clone(),
            ),
            None => ("-".into(), "-".into(), "-".into(), "-".into(), "-".into()),
        };
        let issue_str = issue_port.as_deref().unwrap_or("-");
        let bru_str = if bru_has_pc {
            format!("V={} R={}", if bru_valid { "1" } else { "0" }, if bru_ready { "1" } else { "0" })
        } else {
            "-".into()
        };

        if iq_in || iq_ent.is_some() || issue_port.is_some() || bru_has_pc {
            println!("{:>6} | {:12} | {:8} | {:4} {:4} {:4} | {:4} | {:12} {:12} | {:6} {:6}",
                t, iq_in_str, ent_str, valid_str, r1_str, r2_str, ft_str, issue_str, bru_str, count, if flush { "1" } else { "0" });
        }
    }
    println!("\nNote: valid=0 with matching PC may mean entry was dequeued (issued) or flushed.");
    println!("      fu: FuType (BRU=1 for branch). If fu!=BRU, branch was mis-decoded.");
    println!("      BRU_ready: only div_ready may exist in waveform; BRU ready often inlined.");
    Ok(())
}

/// Trace rob_id through full timeline: when it enters ROB, IQ, gets issued, appears in EXU/MemUnit.
fn trace_rob_id_timeline(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    rid_bin: &str,
    start: wellen::Time,
    end: Option<wellen::Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob_id") && (name.contains("rob.enq") || name.contains("iq.entries_") || name.contains("iq.io_in") || name.contains("exu.") || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_") && (name.contains("valid") || name.contains("rob_id")))
            || (name.contains("iq.valids_"))
            || (name.contains("rob.enq_req_valid") || name.contains("rob.enq_req_ready"))
            || (name.contains("iq.io_in_valid") || name.contains("iq.io_in_ready"))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let rid_trim = rid_bin.trim();
    let rid_num = u32::from_str_radix(rid_trim, 2).unwrap_or(99);
    let matches = |v: Option<&String>| -> bool {
        v.and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .map(|n| n == rid_num)
            .unwrap_or(false)
    };

    let last_t = time_table.last().copied().unwrap_or(0);
    let end_t = end.unwrap_or(last_t);

    println!("Tracing rob_id={} (slot {}) through pipeline, t={}..{}\n", rid_trim, rid_num, start, end_t);

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end_t {
            break;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let mut events: Vec<String> = Vec::new();

        let enq_valid = get_val("rob.enq_req_valid", &vals).map_or(false, |v| v == "1");
        let enq_ready = get_val("rob.enq_req_ready", &vals).map_or(false, |v| v == "1");
        let enq_rid = get_val("rob.enq_rob_id", &vals);
        if enq_valid && enq_ready && matches(enq_rid.as_ref()) {
            events.push("ROB_ENQ".into());
        }

        let iq_in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_in_rid = get_val("iq.io_in_bits_rob_id", &vals);
        if iq_in_valid && matches(iq_in_rid.as_ref()) {
            events.push("IQ_IN".into());
        }

        for entry in 0..8 {
            let v = get_val(&format!("iq.entries_{}_rob_id", entry), &vals);
            if matches(v.as_ref()) {
                let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |x| x == "1");
                events.push(format!("IQ_ent{} valid={}", entry, valid));
            }
        }

        for (port, name) in [("alu", "ALU"), ("bru", "BRU"), ("agu", "AGU"), ("mul", "MUL"), ("div", "DIV"), ("sysu", "SYSU")] {
            let v = get_val(&format!("io_issuePorts_{}_valid", port), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("io_issuePorts_{}_bits_rob_id", port), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("ISSUE_{}", name));
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_rob_id", pipe), &vals);
            if matches(r.as_ref()) {
                events.push(format!("EXU_pipe{}", if pipe.is_empty() { "0" } else { &pipe[1..] }));
            }
        }

        for fu in ["alu", "bru", "agu", "sysu"] {
            let r = get_val(&format!("exu.{}.io_in_bits_rob_id", fu), &vals);
            if matches(r.as_ref()) {
                let v = get_val(&format!("exu.{}.io_in_valid", fu), &vals).map_or(false, |x| x == "1");
                if v {
                    events.push(format!("{}_in", fu.to_uppercase()));
                }
            }
        }
        for fu in ["mul", "div"] {
            let r = get_val(&format!("exu.{}.io_in_bits_rob_id", fu), &vals);
            if matches(r.as_ref()) {
                events.push(format!("{}_in", fu.to_uppercase()));
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("memUnit.ls_slots_{}_rob_id", ls), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("MemUnit_ls{}", ls));
            }
        }

        if !events.is_empty() {
            println!("t={} | {}", t, events.join(" "));
        }
    }
    Ok(())
}

/// Trace p_rd (producer) through timeline: when instruction producing this PR appears.
fn trace_p_rd_timeline(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    pr_bin: &str,
    start: wellen::Time,
    end: Option<wellen::Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("p_rd") && (name.contains("rob.enq") || name.contains("iq.entries_") || name.contains("iq.io_in") || name.contains("exu.") || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_") && (name.contains("valid") || name.contains("p_rd")))
            || (name.contains("rob.slots_p_rd_") || name.contains("rob.slots_is_done_"))
            || (name.contains("rob.enq_req_valid") || name.contains("rob.enq_req_ready"))
            || (name.contains("iq.io_in_valid"))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let pr_trim = pr_bin.trim();
    let last_t = time_table.last().copied().unwrap_or(0);
    let end_t = end.unwrap_or(last_t);

    println!("Tracing p_rd={} (PR{}) through pipeline, t={}..{}\n", pr_trim, u32::from_str_radix(pr_trim, 2).unwrap_or(99), start, end_t);

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end_t {
            break;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let mut events: Vec<String> = Vec::new();
        let matches = |v: Option<&String>| v.as_deref().map(|s| s.trim()) == Some(pr_trim);

        let enq_valid = get_val("rob.enq_req_valid", &vals).map_or(false, |v| v == "1");
        let enq_ready = get_val("rob.enq_req_ready", &vals).map_or(false, |v| v == "1");
        let enq_p_rd = get_val("rob.enq_req_bits_p_rd", &vals);
        if enq_valid && enq_ready && matches(enq_p_rd.as_ref()) {
            events.push("ROB_ENQ".into());
        }

        let iq_in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_in_p_rd = get_val("iq.io_in_bits_p_rd", &vals);
        if iq_in_valid && matches(iq_in_p_rd.as_ref()) {
            events.push("IQ_IN".into());
        }

        for entry in 0..8 {
            let v = get_val(&format!("iq.entries_{}_p_rd", entry), &vals);
            if matches(v.as_ref()) {
                let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |x| x == "1");
                events.push(format!("IQ_ent{} valid={}", entry, valid));
            }
        }

        for (port, name) in [("alu", "ALU"), ("bru", "BRU"), ("agu", "AGU"), ("mul", "MUL"), ("div", "DIV"), ("sysu", "SYSU")] {
            let v = get_val(&format!("io_issuePorts_{}_valid", port), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("io_issuePorts_{}_bits_p_rd", port), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("ISSUE_{}", name));
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
            if matches(r.as_ref()) {
                events.push(format!("EXU_pipe{}", if pipe.is_empty() { "0" } else { &pipe[1..] }));
            }
        }

        for slot in 0..16 {
            let v = get_val(&format!("rob.slots_p_rd_{}", slot), &vals);
            if matches(v.as_ref()) {
                let done = get_val(&format!("rob.slots_is_done_{}", slot), &vals).map_or(false, |x| x == "1");
                events.push(format!("ROB_slot{} done={}", slot, done));
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("memUnit.ls_slots_{}_p_rd", ls), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("MemUnit_ls{}", ls));
            }
        }

        if !events.is_empty() {
            println!("t={} | {}", t, events.join(" "));
        }
    }
    Ok(())
}

/// Find cycles when rob_id and p_rd were enqueued together (ROB+IQ).
fn find_enq_rob_id_p_rd(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    rid_bin: &str,
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("rob.enq_req_valid") || name.contains("rob.enq_req_ready")
            || name.contains("rob.enq_rob_id") || name.contains("rob.enq_req_bits_p_rd")
            || name.contains("iq.io_in_valid") || name.contains("iq.io_in_bits_rob_id") || name.contains("iq.io_in_bits_p_rd")
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let rid_trim = rid_bin.trim();
    let pr_trim = pr_bin.trim();
    let rid_num = u32::from_str_radix(rid_trim, 2).unwrap_or(99);
    let matches_rid = |v: Option<&String>| {
        v.and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .map(|n| n == rid_num)
            .unwrap_or(false)
    };
    let matches_pr = |v: Option<&String>| v.as_deref().map(|s| s.trim()) == Some(pr_trim);

    println!("Finding enq with rob_id={} (slot {}) AND p_rd={} (PR{})\n", rid_trim, rid_num, pr_trim, u32::from_str_radix(pr_trim, 2).unwrap_or(99));

    for (i, &t) in time_table.iter().enumerate() {
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let enq_valid = get_val("rob.enq_req_valid", &vals).map_or(false, |v| v == "1");
        let enq_ready = get_val("rob.enq_req_ready", &vals).map_or(false, |v| v == "1");
        let enq_rid = get_val("rob.enq_rob_id", &vals);
        let enq_p_rd = get_val("rob.enq_req_bits_p_rd", &vals);
        let iq_in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_in_rid = get_val("iq.io_in_bits_rob_id", &vals);
        let iq_in_p_rd = get_val("iq.io_in_bits_p_rd", &vals);

        if enq_valid && enq_ready && matches_rid(enq_rid.as_ref()) && matches_pr(enq_p_rd.as_ref()) {
            let iq_ok = iq_in_valid && matches_rid(iq_in_rid.as_ref()) && matches_pr(iq_in_p_rd.as_ref());
            println!("t={} *** ROB_ENQ rob_id={} p_rd={} IQ_IN={}", t, rid_trim, pr_trim, iq_ok);
        }
    }
    Ok(())
}

/// Check dispatch sync: when rob_id,p_rd dispatched, did ROB+IQ+LSQ all fire? Trace LSQ lifecycle.
fn dispatch_lsq_check(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    rid_bin: &str,
    pr_bin: &str,
    start: wellen::Time,
    end: Option<wellen::Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("rob.enq_req_valid") || name.contains("rob.enq_req_ready")
            || name.contains("rob.enq_rob_id") || name.contains("rob.enq_req_bits_p_rd")
            || (name.contains("iq.io_in") && (name.contains("valid") || name.contains("ready") || name.contains("rob_id") || name.contains("p_rd") || name.contains("fu_type") || name.contains("lsq_id") || name.contains("pc")))
            || (name.contains("idu.io_in") && (name.contains("pc") || name.contains("inst")))
            || (name.contains("ls_alloc") && (name.contains("valid") || name.contains("ready") || name.contains("rob_id") || name.contains("p_rd") || name.contains("lsq_id")))
            || (name.contains("memUnit.ls_slots_") && (name.contains("rob_id") || name.contains("valid") || name.contains("data_ready")))
            || (name.contains("memUnit.io_ls_write") && (name.contains("valid") || name.contains("ready") || name.contains("lsq_id")))
            || (name.contains("iq.entries_") && (name.contains("fu_type") || name.contains("lsq_id") || name.contains("rob_id")))
            || name.contains("iq.valids_")
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let rid_trim = rid_bin.trim();
    let pr_trim = pr_bin.trim();
    let rid_num = u32::from_str_radix(rid_trim, 2).unwrap_or(99);
    let matches_rid = |v: Option<&String>| {
        v.and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .map(|n| n == rid_num)
            .unwrap_or(false)
    };
    let matches_pr = |v: Option<&String>| v.as_deref().map(|s| s.trim()) == Some(pr_trim);

    let last_t = time_table.last().copied().unwrap_or(0);
    let end_t = end.unwrap_or(last_t);

    println!("Dispatch+LSQ check for rob_id={} p_rd={}, t={}..{}\n", rid_trim, pr_trim, start, end_t);

    let mut dispatch_lsq_id: Option<String> = None;
    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end_t {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        let rob_valid = get_val("rob.enq_req_valid", &vals).map_or(false, |v| v == "1");
        let rob_ready = get_val("rob.enq_req_ready", &vals).map_or(false, |v| v == "1");
        let rob_rid = get_val("rob.enq_rob_id", &vals);
        let rob_p_rd = get_val("rob.enq_req_bits_p_rd", &vals);
        let iq_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_rid = get_val("iq.io_in_bits_rob_id", &vals);
        let iq_p_rd = get_val("iq.io_in_bits_p_rd", &vals);
        let iq_fu = get_val("iq.io_in_bits_fu_type", &vals);
        let iq_pc = get_val("iq.io_in_bits_pc", &vals);
        let _iq_lsq = get_val("iq.io_in_bits_lsq_id", &vals);
        let ls_valid = get_val("ls_alloc_valid", &vals).map_or(false, |v| v == "1");
        let ls_ready = get_val("ls_alloc_ready", &vals).map_or(false, |v| v == "1");
        let ls_rid = get_val("ls_alloc_bits_rob_id", &vals);
        let ls_p_rd = get_val("ls_alloc_bits_p_rd", &vals);
        let ls_lsq_id = get_val("ls_alloc_lsq_id", &vals);

        if rob_valid && rob_ready && matches_rid(rob_rid.as_ref()) && matches_pr(rob_p_rd.as_ref()) {
            let rob_fire = true;
            let iq_fire = iq_valid && matches_rid(iq_rid.as_ref()) && matches_pr(iq_p_rd.as_ref());
            let ls_fire = ls_valid && ls_ready && matches_rid(ls_rid.as_ref()) && matches_pr(ls_p_rd.as_ref());
            let fu_type = iq_fu.as_deref().map(|s| s.trim()).unwrap_or("?");
            let lsq_id = ls_lsq_id.as_deref().map(|s| s.trim()).unwrap_or("?");
            let pc_hex = iq_pc.as_ref().and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|n| format!("0x{:08x}", n)).unwrap_or_else(|| "?".into());
            if ls_fire {
                dispatch_lsq_id = Some(lsq_id.to_string());
            }
            println!(
                "t={} DISPATCH: ROB_ENQ={} IQ_IN={} LS_ALLOC={} (valid={} ready={}) fu_type={} lsq_id={} pc={}",
                t, rob_fire, iq_fire, ls_fire, ls_valid, ls_ready, fu_type, lsq_id, pc_hex
            );
            if iq_fire && !ls_fire && (fu_type == "010" || fu_type == "10") {
                println!("  *** MISMATCH: LSU instruction but LS_ALLOC did NOT fire! Instruction in ROB+IQ but NOT in LSQ! ***");
            }
            if iq_fire && !ls_fire {
                println!("  >>> Checking IQ entry fu_type in next cycles (instruction may be LSU but ls_alloc.valid was false)");
            }
        }
    }

    println!("\n--- IDU input inst when PC=0x80005cb0 (t=108..118) ---");
    let pc_target = 0x80005cb0u32;
    let pc_bin = format!("{:032b}", pc_target);
    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end_t || t < 108 || t > 118 {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }
        let idu_pc = get_val("idu.io_in_bits_pc", &vals).or_else(|| get_val("iq.io_in_bits_pc", &vals));
        if idu_pc.as_deref().map(|s| s.trim()) == Some(pc_bin.as_str()) {
            let inst = get_val("idu.io_in_bits_inst", &vals).or_else(|| get_val("idu.io_in_bits_r_inst", &vals));
            let inst_hex = inst.as_ref().and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|n| format!("0x{:08x}", n)).unwrap_or_else(|| "?".into());
            println!("  t={} idu.io_in pc=0x{:08x} inst={}", t, pc_target, inst_hex);
        }
    }

    println!("\n--- IQ entry state for rob_id={} after dispatch (t=113..125) ---\n", rid_trim);
    for (i, &t) in time_table.iter().enumerate() {
        if t < start || t > end_t || t < 113 || t > 125 {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }
        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
            let e_rid = get_val(&format!("iq.entries_{}_rob_id", entry), &vals);
            if valid && matches_rid(e_rid.as_ref()) {
                let e_fu: String = get_val(&format!("iq.entries_{}_fu_type", entry), &vals).as_deref().map(|s| s.trim().to_string()).unwrap_or_else(|| "?".into());
                let e_lsq: String = get_val(&format!("iq.entries_{}_lsq_id", entry), &vals).as_deref().map(|s| s.trim().to_string()).unwrap_or_else(|| "?".into());
                println!("t={} IQ entry{}: fu_type={} lsq_id={} (LSU=010)", t, entry, e_fu, e_lsq);
            }
        }
    }

    if let Some(ref lsq_id) = dispatch_lsq_id {
        println!("\n--- Tracing LSQ slot {} (rob_id={}) lifecycle ---\n", lsq_id, rid_trim);
        let lsq_num: usize = u32::from_str_radix(lsq_id.trim(), 2).unwrap_or(99) as usize;
        let slot_pattern = format!("ls_slots_{}_", lsq_num);

        for (i, &t) in time_table.iter().enumerate() {
            if t < start || t > end_t {
                continue;
            }
            let idx = i as u32;
            let mut vals: Vec<(String, String)> = Vec::new();
            for (name, sig_ref) in &sigs {
                if let Some(sig) = wf.get_signal(*sig_ref) {
                    if let Some(offset) = sig.get_offset(idx) {
                        let val = sig.get_value_at(&offset, 0).to_string();
                        vals.push((name.clone(), val));
                    }
                }
            }
            let slot_valid = get_val(&format!("ls_slots_{}_valid", lsq_num), &vals).map_or(false, |v| v == "1");
            let slot_rob = get_val(&format!("ls_slots_{}_rob_id", lsq_num), &vals);
            let slot_ready = get_val(&format!("ls_slots_{}_data_ready", lsq_num), &vals).map_or(false, |v| v == "1");
            let ls_write_valid = get_val("ls_write_valid", &vals).map_or(false, |v| v == "1");
            let ls_write_lsq = get_val("ls_write_bits_lsq_id", &vals);
            let write_to_slot = ls_write_valid
                && ls_write_lsq.as_ref().and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                    == Some(lsq_num as u32);

            if slot_valid && matches_rid(slot_rob.as_ref()) {
                println!(
                    "t={} slot{}: valid={} data_ready={} ls_write_to_slot={}",
                    t, lsq_num, slot_valid, slot_ready, write_to_slot
                );
            }
        }
    }
    Ok(())
}

/// Find instruction with rob_id in IQ, EXU pipeline, MemUnit.
fn find_rob_id_in_pipeline(
    wf: &mut wellen::simple::Waveform,
    time_table: &[wellen::Time],
    rid_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob_id") && (name.contains("iq.entries_") || name.contains("exu.") || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_") && (name.contains("valid") || name.contains("rob_id")))
            || (name.contains("iq.valids_"))
        {
            sigs.push((name, var.signal_ref()));
        }
    }
    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    let get_val = |name_contains: &str, vals: &[(String, String)]| -> Option<String> {
        vals.iter()
            .find(|(n, _)| n.contains(name_contains))
            .map(|(_, v)| v.clone())
    };

    let rid_trim = rid_bin.trim();
    let last_t = time_table.last().copied().unwrap_or(0);
    let start = last_t.saturating_sub(200);

    println!("Finding rob_id={} (slot {}) in pipeline, t={}..{}\n", rid_trim, u32::from_str_radix(rid_trim, 2).unwrap_or(99), start, last_t);

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        let idx = i as u32;
        let mut vals: Vec<(String, String)> = Vec::new();
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    vals.push((name.clone(), val));
                }
            }
        }

        for entry in 0..8 {
            let v = get_val(&format!("iq.entries_{}_rob_id", entry), &vals);
            if v.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                println!("t={} IQ entry{}: rob_id={} valid={}", t, entry, rid_trim, valid);
            }
        }

        for (port, name) in [("alu", "ALU"), ("bru", "BRU"), ("agu", "AGU"), ("mul", "MUL"), ("div", "DIV"), ("sysu", "SYSU")] {
            let v = get_val(&format!("io_issuePorts_{}_valid", port), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("io_issuePorts_{}_bits_rob_id", port), &vals);
            if v && r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                println!("t={} ISSUE {}: rob_id={} (being issued)", t, name, rid_trim);
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_rob_id", pipe), &vals);
            if r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let p_rd = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
                println!("t={} EXU pipeOut{}: rob_id={} p_rd={:?} (in EX pipeline)", t, pipe, rid_trim, p_rd);
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals).map_or(false, |x| x == "1");
            let r = get_val(&format!("memUnit.ls_slots_{}_rob_id", ls), &vals);
            if v && r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let p_rd = get_val(&format!("memUnit.ls_slots_{}_p_rd", ls), &vals);
                println!("t={} MemUnit ls_slot{}: rob_id={} p_rd={:?}", t, ls, rid_trim, p_rd);
            }
        }
    }
    Ok(())
}

/// Find TimeTableIdx for the largest time <= t. wellen uses 0-based indices.
fn find_time_idx_at_or_before(time_table: &[Time], t: Time) -> Option<TimeTableIdx> {
    if time_table.is_empty() {
        return None;
    }
    let mut best = None;
    for (i, &ti) in time_table.iter().enumerate() {
        if ti <= t {
            best = Some(i as u32);
        } else {
            break;
        }
    }
    best
}
