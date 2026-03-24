use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Timeline trace: dump commits and enqs with ROB head/tail, FreeList, RMT for bug analysis.
pub fn timeline_trace(
    wf: &mut Waveform,
    time_table: &[Time],
    start: Time,
    end: Time,
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
            if name.contains("core.rob")
                || name.contains("core.idu.freeList")
                || name.contains("core.idu.rmt")
            {
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
            enqs.push((
                t,
                rd.to_string(),
                p_rd.to_string(),
                old.to_string(),
                rob_id.to_string(),
            ));
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
        let idx_108 = time_table
            .iter()
            .position(|&ti| ti == 108)
            .map(|i| i as u32);
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
                let last_flush_before_108 = flushes
                    .iter()
                    .filter(|&&f| f < 108)
                    .last()
                    .copied()
                    .unwrap_or(0);
                let head_num = u32::from_str_radix(head_bin, 2).unwrap_or(0);
                let enq_for_slot: Vec<_> = enqs
                    .iter()
                    .filter(|(t, rd_e, p_e, old_e, rid)| {
                        *t > last_flush_before_108
                            && u32::from_str_radix(rid.trim(), 2).unwrap_or(99) == head_num
                            && rd_e == rd
                            && p_e == p_rd
                            && old_e == old
                    })
                    .collect();
                println!(
                    "\n  Enqueues into slot {} (rob_id={}) matching commit rd={} p_rd={} old_p_rd={}:",
                    head_bin, head_bin, rd, p_rd, old
                );
                for (t, rd, p_rd, old, _) in &enq_for_slot {
                    println!(
                        "    t={}: rd_index={}, p_rd={}, old_p_rd={}",
                        t, rd, p_rd, old
                    );
                }
                if let Some((t_disp, rd_d, p_d, old_d)) = enq_for_slot
                    .first()
                    .map(|(t, rd, p, o, _)| (*t, rd.clone(), p.clone(), o.clone()))
                {
                    println!("\n  *** DISPATCH CYCLE for t=108 commit: t={} ***", t_disp);
                    println!(
                        "  At dispatch: rd_index={}, p_rd={}, old_p_rd={}",
                        rd_d, p_d, old_d
                    );

                    // Get FreeList and RMT at dispatch cycle
                    let idx_disp = time_table
                        .iter()
                        .position(|&ti| ti == t_disp)
                        .map(|i| i as u32);
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
                                        } else if name.ends_with("table_1") && name.contains("rmt")
                                        {
                                            table_1 = val;
                                        }
                                    }
                                }
                            }
                            // buf[head] - head is index. Parse head as decimal
                            let head_idx: usize =
                                u32::from_str_radix(fl_head.trim(), 2).unwrap_or(0) as usize;
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
                                    if name.contains(&format!("buf_{}", head_idx))
                                        && name.contains("freeList")
                                    {
                                        if let Some(sig) = wf.get_signal(*sig_ref) {
                                            if let Some(offset) = sig.get_offset(idx_d) {
                                                buf_head_val =
                                                    sig.get_value_at(&offset, 0).to_string();
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                            println!(
                                "  FreeList at t={}: head={}, tail={}, buf(head)={}",
                                t_disp, fl_head, fl_tail, buf_head_val
                            );
                            println!("  RMT table_1(sp) at t={}: {}", t_disp, table_1);
                        }
                    }
                }
            }
        }
    }

    // Last commit before t=108 instruction's dispatch
    println!("\n## Commits between t=10 and t=108 (in dispatch order)");
    let commits_10_108: Vec<_> = commits
        .iter()
        .filter(|(t, _, _, _)| *t >= 10 && *t <= 108)
        .collect();
    for (t, rd, p_rd, old) in &commits_10_108 {
        let effect = if old == p_rd { "reuse" } else { "push" };
        println!(
            "  t={}: rd={}, p_rd={}, old_p_rd={} {}",
            t, rd, p_rd, old, effect
        );
    }

    Ok(())
}
