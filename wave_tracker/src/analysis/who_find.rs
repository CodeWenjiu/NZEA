use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Find cycles where prf_write or commit has p_rd matching the given PR (binary, e.g. "100101").
pub fn who_produces_pr(
    wf: &mut Waveform,
    time_table: &[Time],
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.io_prf_write_")
            && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("commit.io_rob_commit")
                && (name.contains("valid") || name.contains("p_rd")))
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
        let cp = get_val("commit.io_rob_commit_bits_p_rd", &vals)
            .as_deref()
            .map(|s| s.trim().to_string());
        if cv && cp.as_deref() == Some(pr_trim) {
            let rd = get_val("commit.io_rob_commit_bits_rd_index", &vals);
            println!(
                "t={} COMMIT rd_index={:?} p_rd={} -> PR {}",
                t, rd, pr_trim, pr_trim
            );
        }
    }
    Ok(())
}

/// Find instruction with p_rd=PR in IQ, ROB, EXU pipeline, MemUnit, issue ports.
pub fn find_p_rd_in_pipeline(
    wf: &mut Waveform,
    time_table: &[Time],
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.entries_")
            && (name.contains("p_rd")
                || name.contains("valids_")
                || name.contains("rs1_ready")
                || name.contains("rs2_ready")
                || name.contains("fu_type")
                || name.contains("pc")))
            || (name.contains("rob.slots_p_rd_")
                || name.contains("rob.slots_is_done_")
                || name.contains("rob.slots_mem_type_")
                || name.contains("rob.slots_rd_index_")
                || name.contains("rob.head_ptr")
                || name.contains("rob.tail_ptr"))
            || (name.contains("exu.pipeOut_bits") && name.contains("p_rd"))
            || (name.contains("exu.io_issuePorts_")
                && (name.contains("valid") || name.contains("p_rd")))
            || (name.contains("memUnit.ls_slots_")
                && (name.contains("p_rd") || name.contains("valid")))
            || (name.contains("isu.io_out_bits_p_rd") || name.contains("isu.io_out_valid"))
            || (name.contains("iq.io_issuePorts_")
                && (name.contains("valid") || name.contains("p_rd")))
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

    println!(
        "Finding instruction with p_rd={} (PR{}) in pipeline, t={}..{}\n",
        pr_trim,
        u32::from_str_radix(pr_trim, 2).unwrap_or(99),
        start,
        last_t
    );

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
                let valid =
                    get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
                let pc = get_val(&format!("iq.entries_{}_pc", entry), &vals);
                let ft = get_val(&format!("iq.entries_{}_fu_type", entry), &vals);
                println!(
                    "t={} IQ entry{}: p_rd={} valid={} r1={} r2={} pc={:?} fu={:?}",
                    t, entry, pr_trim, valid, r1, r2, pc, ft
                );
            }
        }

        for slot in 0..16 {
            let v = get_val(&format!("rob.slots_p_rd_{}", slot), &vals);
            if v.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                let done = get_val(&format!("rob.slots_is_done_{}", slot), &vals)
                    .map_or(false, |v| v == "1");
                let head = get_val("rob.head_ptr", &vals);
                let tail = get_val("rob.tail_ptr", &vals);
                let mem_type = get_val(&format!("rob.slots_mem_type_{}", slot), &vals);
                let rd_idx = get_val(&format!("rob.slots_rd_index_{}", slot), &vals);
                println!(
                    "t={} ROB slot{}: p_rd={} is_done={} mem_type={:?} rd_index={:?} head={:?} tail={:?}",
                    t, slot, pr_trim, done, mem_type, rd_idx, head, tail
                );
            }
        }

        for (port, name) in [
            ("alu", "ALU"),
            ("bru", "BRU"),
            ("agu", "AGU"),
            ("mul", "MUL"),
            ("div", "DIV"),
            ("sysu", "SYSU"),
        ] {
            let v = get_val(&format!("iq.io_issuePorts_{}_valid", port), &vals)
                .map_or(false, |x| x == "1");
            let p = get_val(&format!("iq.io_issuePorts_{}_bits_p_rd", port), &vals);
            if v && p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!("t={} ISSUE {}: p_rd={} (being issued)", t, name, pr_trim);
            }
        }

        let isu_valid = get_val("isu.io_out_valid", &vals).map_or(false, |v| v == "1");
        let isu_p = get_val("isu.io_out_bits_p_rd", &vals)
            .or_else(|| get_val("isu.io_out_bits_r_p_rd", &vals));
        if isu_valid && isu_p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
            println!("t={} ISU out: p_rd={} (on way to IQ)", t, pr_trim);
        }

        for pipe in ["", "_1", "_3", "_4", "_5"] {
            let p = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
            if p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!(
                    "t={} EXU pipeOut{}: p_rd={} (in EX pipeline)",
                    t, pipe, pr_trim
                );
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals)
                .map_or(false, |x| x == "1");
            let p = get_val(&format!("memUnit.ls_slots_{}_p_rd", ls), &vals);
            if v && p.as_deref().map(|s| s.trim()) == Some(pr_trim) {
                println!(
                    "t={} MemUnit ls_slot{}: p_rd={} (load/store in flight)",
                    t, ls, pr_trim
                );
            }
        }
    }
    Ok(())
}
