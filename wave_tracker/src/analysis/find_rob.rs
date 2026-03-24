use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Find instruction with rob_id in IQ, EXU pipeline, MemUnit.
pub fn find_rob_id_in_pipeline(
    wf: &mut Waveform,
    time_table: &[Time],
    rid_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob_id")
            && (name.contains("iq.entries_")
                || name.contains("exu.")
                || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_")
                && (name.contains("valid") || name.contains("rob_id")))
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

    println!(
        "Finding rob_id={} (slot {}) in pipeline, t={}..{}\n",
        rid_trim,
        u32::from_str_radix(rid_trim, 2).unwrap_or(99),
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
            let v = get_val(&format!("iq.entries_{}_rob_id", entry), &vals);
            if v.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let valid =
                    get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                println!(
                    "t={} IQ entry{}: rob_id={} valid={}",
                    t, entry, rid_trim, valid
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
            let v = get_val(&format!("io_issuePorts_{}_valid", port), &vals)
                .map_or(false, |x| x == "1");
            let r = get_val(&format!("io_issuePorts_{}_bits_rob_id", port), &vals);
            if v && r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                println!("t={} ISSUE {}: rob_id={} (being issued)", t, name, rid_trim);
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_rob_id", pipe), &vals);
            if r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let p_rd = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
                println!(
                    "t={} EXU pipeOut{}: rob_id={} p_rd={:?} (in EX pipeline)",
                    t, pipe, rid_trim, p_rd
                );
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals)
                .map_or(false, |x| x == "1");
            let r = get_val(&format!("memUnit.ls_slots_{}_rob_id", ls), &vals);
            if v && r.as_deref().map(|s| s.trim()) == Some(rid_trim) {
                let p_rd = get_val(&format!("memUnit.ls_slots_{}_p_rd", ls), &vals);
                println!(
                    "t={} MemUnit ls_slot{}: rob_id={} p_rd={:?}",
                    t, ls, rid_trim, p_rd
                );
            }
        }
    }
    Ok(())
}
