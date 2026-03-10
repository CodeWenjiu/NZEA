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
        let idx = (i + 1) as u32;
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
        let idx = (i + 1) as u32;

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
        let idx = (i + 1) as u32;
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
        let idx_108 = time_table.iter().position(|&ti| ti == 108).map(|i| (i + 1) as u32);
        if let Some(idx) = idx_108 {
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
                let idx_disp = time_table.iter().position(|&ti| ti == t_disp).map(|i| (i + 1) as u32);
                if let Some(idx_d) = idx_disp {
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

    // Last commit before t=108 instruction's dispatch
    println!("\n## Commits between t=10 and t=108 (in dispatch order)");
    let commits_10_108: Vec<_> = commits.iter().filter(|(t, _, _, _)| *t >= 10 && *t <= 108).collect();
    for (t, rd, p_rd, old) in &commits_10_108 {
        let effect = if old == p_rd { "reuse" } else { "push" };
        println!("  t={}: rd={}, p_rd={}, old_p_rd={} {}", t, rd, p_rd, old, effect);
    }

    Ok(())
}

/// Find TimeTableIdx for the largest time <= t. wellen uses 1-based indices.
fn find_time_idx_at_or_before(time_table: &[Time], t: Time) -> Option<TimeTableIdx> {
    if time_table.is_empty() {
        return None;
    }
    let mut best = None;
    for (i, &ti) in time_table.iter().enumerate() {
        if ti <= t {
            best = Some((i + 1) as u32);
        } else {
            break;
        }
    }
    best
}
