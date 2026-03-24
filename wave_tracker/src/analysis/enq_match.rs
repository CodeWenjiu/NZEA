use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Find cycles when rob_id and p_rd were enqueued together (ROB+IQ).
pub fn find_enq_rob_id_p_rd(
    wf: &mut Waveform,
    time_table: &[Time],
    rid_bin: &str,
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("rob.enq_req_valid")
            || name.contains("rob.enq_req_ready")
            || name.contains("rob.enq_rob_id")
            || name.contains("rob.enq_req_bits_p_rd")
            || name.contains("iq.io_in_valid")
            || name.contains("iq.io_in_bits_rob_id")
            || name.contains("iq.io_in_bits_p_rd")
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

    println!(
        "Finding enq with rob_id={} (slot {}) AND p_rd={} (PR{})\n",
        rid_trim,
        rid_num,
        pr_trim,
        u32::from_str_radix(pr_trim, 2).unwrap_or(99)
    );

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

        if enq_valid && enq_ready && matches_rid(enq_rid.as_ref()) && matches_pr(enq_p_rd.as_ref())
        {
            let iq_ok =
                iq_in_valid && matches_rid(iq_in_rid.as_ref()) && matches_pr(iq_in_p_rd.as_ref());
            println!(
                "t={} *** ROB_ENQ rob_id={} p_rd={} IQ_IN={}",
                t, rid_trim, pr_trim, iq_ok
            );
        }
    }
    Ok(())
}
