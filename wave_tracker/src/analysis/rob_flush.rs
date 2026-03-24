use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Scan for ROB-IQ desync: IQ full but rob_enq fired (instruction entered ROB but not IQ).
pub fn rob_iq_desync_scan(
    wf: &mut Waveform,
    time_table: &[Time],
    start: Time,
    end: Time,
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

    println!(
        "Scanning for ROB-IQ desync: IQ full but rob_enq fired, t={}..{}\n",
        start, end
    );

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
            println!(
                "t={} *** ROB-IQ DESYNC: IQ in_ready=0 but rob_enq fired (instruction entered ROB, not IQ) count={:?}",
                t, iq_count
            );
        }
    }
    println!("\nTotal ROB-IQ desync events found: {}", found);
    Ok(())
}

/// Scan for flush timing: when do_flush/iq.flush change, check ROB vs IQ sync.
pub fn flush_sync_scan(
    wf: &mut Waveform,
    time_table: &[Time],
    start: Time,
    end: Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("rob") && name.contains("do_flush"))
            || (name.contains("iq") && name.contains("flush"))
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

        let rob_flush = get_val("rob.io_do_flush", &vals)
            .or_else(|| get_val("do_flush", &vals))
            .map(|v| v == "1");
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
