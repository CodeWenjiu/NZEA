use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Dump IQ state for last N cycles to find all-blocked deadlock.
pub fn deadlock_tail(
    wf: &mut Waveform,
    time_table: &[Time],
    n: u64,
) -> Result<(), Box<dyn std::error::Error>> {
    if time_table.is_empty() {
        println!("Empty waveform.");
        return Ok(());
    }
    let total = time_table.len();
    let start_idx = if total as u64 > n {
        total - n as usize
    } else {
        0
    };
    let t_start = time_table[start_idx];

    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("core.iq.valids_")
            || (name.contains("core.iq.entries_")
                && (name.contains("fu_type")
                    || name.contains("p_rs1")
                    || name.contains("p_rs2")
                    || name.contains("p_rd")
                    || name.contains("rs1_ready")
                    || name.contains("rs2_ready")
                    || name.contains("rob_id")))
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

    println!(
        "Last {} cycles (t={} to t={})\n",
        n,
        t_start,
        time_table.last().copied().unwrap_or(0)
    );
    let mut all_blocked_at: Option<Time> = None;

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

        let count = get_val("iq.count", &vals)
            .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .unwrap_or(99);
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
            let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                .map_or(false, |v| v == "1");
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
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
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
        let desync_note = if valids_desync {
            " [count/valids DESYNC - using entries]"
        } else {
            ""
        };
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
            let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            let pr1_ready = p1
                .as_ref()
                .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|pr| {
                    let (b, i) = ((pr >> 4) as usize, (pr & 0xF) as usize);
                    get_val(&format!("bank_ready_{}_{}", b, i), &vals).map_or(false, |v| v == "1")
                })
                .unwrap_or(true);
            let pr2_ready = p2
                .as_ref()
                .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|pr| {
                    if pr == 0 {
                        true
                    } else {
                        let (b, i) = ((pr >> 4) as usize, (pr & 0xF) as usize);
                        get_val(&format!("bank_ready_{}_{}", b, i), &vals)
                            .map_or(false, |v| v == "1")
                    }
                })
                .unwrap_or(true);
            let rob_str = rob_id
                .as_deref()
                .map(|s| format!(" rob_id={}", s.trim()))
                .unwrap_or_default();
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
                entry,
                fu_name(ft),
                rob_str,
                p_rd,
                p1,
                p2,
                r1,
                r2,
                pr1_ready,
                pr2_ready,
                why,
                mm
            );
        }
    }
    if let Some(t) = all_blocked_at {
        println!("\n*** Deadlock: all IQ entries blocked from t={}", t);
    }
    Ok(())
}
