use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Trace p_rd (producer) through timeline: when instruction producing this PR appears.
pub fn trace_p_rd_timeline(
    wf: &mut Waveform,
    time_table: &[Time],
    pr_bin: &str,
    start: Time,
    end: Option<Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("p_rd")
            && (name.contains("rob.enq")
                || name.contains("iq.entries_")
                || name.contains("iq.io_in")
                || name.contains("exu.")
                || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_")
                && (name.contains("valid") || name.contains("p_rd")))
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

    println!(
        "Tracing p_rd={} (PR{}) through pipeline, t={}..{}\n",
        pr_trim,
        u32::from_str_radix(pr_trim, 2).unwrap_or(99),
        start,
        end_t
    );

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
                let valid =
                    get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |x| x == "1");
                events.push(format!("IQ_ent{} valid={}", entry, valid));
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
            let r = get_val(&format!("io_issuePorts_{}_bits_p_rd", port), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("ISSUE_{}", name));
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_p_rd", pipe), &vals);
            if matches(r.as_ref()) {
                events.push(format!(
                    "EXU_pipe{}",
                    if pipe.is_empty() { "0" } else { &pipe[1..] }
                ));
            }
        }

        for slot in 0..16 {
            let v = get_val(&format!("rob.slots_p_rd_{}", slot), &vals);
            if matches(v.as_ref()) {
                let done = get_val(&format!("rob.slots_is_done_{}", slot), &vals)
                    .map_or(false, |x| x == "1");
                events.push(format!("ROB_slot{} done={}", slot, done));
            }
        }

        for ls in 0..8 {
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals)
                .map_or(false, |x| x == "1");
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
