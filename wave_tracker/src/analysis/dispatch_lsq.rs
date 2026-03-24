use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Check dispatch sync: when rob_id,p_rd dispatched, did ROB+IQ+LSQ all fire? Trace LSQ lifecycle.
pub fn dispatch_lsq_check(
    wf: &mut Waveform,
    time_table: &[Time],
    rid_bin: &str,
    pr_bin: &str,
    start: Time,
    end: Option<Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("rob.enq_req_valid")
            || name.contains("rob.enq_req_ready")
            || name.contains("rob.enq_rob_id")
            || name.contains("rob.enq_req_bits_p_rd")
            || (name.contains("iq.io_in")
                && (name.contains("valid")
                    || name.contains("ready")
                    || name.contains("rob_id")
                    || name.contains("p_rd")
                    || name.contains("fu_type")
                    || name.contains("lsq_id")
                    || name.contains("pc")))
            || (name.contains("idu.io_in") && (name.contains("pc") || name.contains("inst")))
            || (name.contains("ls_alloc")
                && (name.contains("valid")
                    || name.contains("ready")
                    || name.contains("rob_id")
                    || name.contains("p_rd")
                    || name.contains("lsq_id")))
            || (name.contains("memUnit.ls_slots_")
                && (name.contains("rob_id")
                    || name.contains("valid")
                    || name.contains("data_ready")))
            || (name.contains("memUnit.io_ls_write")
                && (name.contains("valid") || name.contains("ready") || name.contains("lsq_id")))
            || (name.contains("iq.entries_")
                && (name.contains("fu_type") || name.contains("lsq_id") || name.contains("rob_id")))
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

    println!(
        "Dispatch+LSQ check for rob_id={} p_rd={}, t={}..{}\n",
        rid_trim, pr_trim, start, end_t
    );

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

        if rob_valid && rob_ready && matches_rid(rob_rid.as_ref()) && matches_pr(rob_p_rd.as_ref())
        {
            let rob_fire = true;
            let iq_fire = iq_valid && matches_rid(iq_rid.as_ref()) && matches_pr(iq_p_rd.as_ref());
            let ls_fire = ls_valid
                && ls_ready
                && matches_rid(ls_rid.as_ref())
                && matches_pr(ls_p_rd.as_ref());
            let fu_type = iq_fu.as_deref().map(|s| s.trim()).unwrap_or("?");
            let lsq_id = ls_lsq_id.as_deref().map(|s| s.trim()).unwrap_or("?");
            let pc_hex = iq_pc
                .as_ref()
                .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|n| format!("0x{:08x}", n))
                .unwrap_or_else(|| "?".into());
            if ls_fire {
                dispatch_lsq_id = Some(lsq_id.to_string());
            }
            println!(
                "t={} DISPATCH: ROB_ENQ={} IQ_IN={} LS_ALLOC={} (valid={} ready={}) fu_type={} lsq_id={} pc={}",
                t, rob_fire, iq_fire, ls_fire, ls_valid, ls_ready, fu_type, lsq_id, pc_hex
            );
            if iq_fire && !ls_fire && (fu_type == "010" || fu_type == "10") {
                println!(
                    "  *** MISMATCH: LSU instruction but LS_ALLOC did NOT fire! Instruction in ROB+IQ but NOT in LSQ! ***"
                );
            }
            if iq_fire && !ls_fire {
                println!(
                    "  >>> Checking IQ entry fu_type in next cycles (instruction may be LSU but ls_alloc.valid was false)"
                );
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
        let idu_pc =
            get_val("idu.io_in_bits_pc", &vals).or_else(|| get_val("iq.io_in_bits_pc", &vals));
        if idu_pc.as_deref().map(|s| s.trim()) == Some(pc_bin.as_str()) {
            let inst = get_val("idu.io_in_bits_inst", &vals)
                .or_else(|| get_val("idu.io_in_bits_r_inst", &vals));
            let inst_hex = inst
                .as_ref()
                .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
                .map(|n| format!("0x{:08x}", n))
                .unwrap_or_else(|| "?".into());
            println!(
                "  t={} idu.io_in pc=0x{:08x} inst={}",
                t, pc_target, inst_hex
            );
        }
    }

    println!(
        "\n--- IQ entry state for rob_id={} after dispatch (t=113..125) ---\n",
        rid_trim
    );
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
                let e_fu: String = get_val(&format!("iq.entries_{}_fu_type", entry), &vals)
                    .as_deref()
                    .map(|s| s.trim().to_string())
                    .unwrap_or_else(|| "?".into());
                let e_lsq: String = get_val(&format!("iq.entries_{}_lsq_id", entry), &vals)
                    .as_deref()
                    .map(|s| s.trim().to_string())
                    .unwrap_or_else(|| "?".into());
                println!(
                    "t={} IQ entry{}: fu_type={} lsq_id={} (LSU=010)",
                    t, entry, e_fu, e_lsq
                );
            }
        }
    }

    if let Some(ref lsq_id) = dispatch_lsq_id {
        println!(
            "\n--- Tracing LSQ slot {} (rob_id={}) lifecycle ---\n",
            lsq_id, rid_trim
        );
        let lsq_num: usize = u32::from_str_radix(lsq_id.trim(), 2).unwrap_or(99) as usize;

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
            let slot_valid =
                get_val(&format!("ls_slots_{}_valid", lsq_num), &vals).map_or(false, |v| v == "1");
            let slot_rob = get_val(&format!("ls_slots_{}_rob_id", lsq_num), &vals);
            let slot_ready = get_val(&format!("ls_slots_{}_data_ready", lsq_num), &vals)
                .map_or(false, |v| v == "1");
            let ls_write_valid = get_val("ls_write_valid", &vals).map_or(false, |v| v == "1");
            let ls_write_lsq = get_val("ls_write_bits_lsq_id", &vals);
            let write_to_slot = ls_write_valid
                && ls_write_lsq
                    .as_ref()
                    .and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
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
