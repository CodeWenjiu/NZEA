use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Trace rob_id through full timeline: when it enters ROB, IQ, gets issued, appears in EXU/MemUnit.
pub fn trace_rob_id_timeline(
    wf: &mut Waveform,
    time_table: &[Time],
    rid_bin: &str,
    start: Time,
    end: Option<Time>,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob_id")
            && (name.contains("rob.enq")
                || name.contains("iq.entries_")
                || name.contains("iq.io_in")
                || name.contains("exu.")
                || name.contains("memUnit.ls_slots_")))
            || (name.contains("io_issuePorts_")
                && (name.contains("valid") || name.contains("rob_id")))
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

    println!(
        "Tracing rob_id={} (slot {}) through pipeline, t={}..{}\n",
        rid_trim, rid_num, start, end_t
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
            let r = get_val(&format!("io_issuePorts_{}_bits_rob_id", port), &vals);
            if v && matches(r.as_ref()) {
                events.push(format!("ISSUE_{}", name));
            }
        }

        for pipe in ["", "_1", "_2", "_3", "_4", "_5"] {
            let r = get_val(&format!("exu.pipeOut_bits_r{}_rob_id", pipe), &vals);
            if matches(r.as_ref()) {
                events.push(format!(
                    "EXU_pipe{}",
                    if pipe.is_empty() { "0" } else { &pipe[1..] }
                ));
            }
        }

        for fu in ["alu", "bru", "agu", "sysu"] {
            let r = get_val(&format!("exu.{}.io_in_bits_rob_id", fu), &vals);
            if matches(r.as_ref()) {
                let v =
                    get_val(&format!("exu.{}.io_in_valid", fu), &vals).map_or(false, |x| x == "1");
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
            let v = get_val(&format!("memUnit.ls_slots_{}_valid", ls), &vals)
                .map_or(false, |x| x == "1");
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
