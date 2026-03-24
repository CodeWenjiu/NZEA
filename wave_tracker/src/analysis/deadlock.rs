use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Deadlock analysis: find first PRF-IQ mismatch, then dump prf_write/bypass/IQ state around it.
pub fn deadlock_analysis(
    wf: &mut Waveform,
    time_table: &[Time],
    start: Time,
    end: Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("core.iq.valids_")
            || (name.contains("core.iq.entries_")
                && (name.contains("p_rs1")
                    || name.contains("p_rs2")
                    || name.contains("rs1_ready")
                    || name.contains("rs2_ready")))
            || name.contains("core.isu.bank_ready_")
            || (name.contains("core.iq.io_prf_write_")
                && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("core.iq.io_bypass_level1_")
                && (name.contains("_valid") || name.contains("_bits_addr")))
            || (name.contains("core.iq.io_in_")
                && (name.contains("valid")
                    || name.contains("ready")
                    || name.contains("bits_p_rs1")
                    || name.contains("bits_p_rs2")
                    || name.contains("bits_rs1_ready")
                    || name.contains("bits_rs2_ready")))
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

    const PR2_BIN: &str = "000010";
    let mut first_mismatch: Option<(Time, usize, &str)> = None;
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
        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
            if !valid {
                continue;
            }
            let p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
            let rs2_ready = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            if let Some(pr_bin) = &p_rs2 {
                if pr_bin.trim() == PR2_BIN && !rs2_ready {
                    let bank = 0;
                    let idx_bank = 2;
                    let bank_suffix = format!("bank_ready_{}_{}", bank, idx_bank);
                    let prf_ready = get_val(&bank_suffix, &vals).map_or(false, |v| v == "1");
                    if prf_ready {
                        first_mismatch = Some((t, entry, "rs2"));
                        break;
                    }
                }
            }
        }
        if first_mismatch.is_some() {
            break;
        }
    }

    let Some((t_mismatch, entry, src)) = first_mismatch else {
        println!("No PRF-IQ mismatch found in range.");
        return Ok(());
    };

    println!(
        "First PRF-IQ mismatch at t={}, entry={}, {} (PR2/sp)",
        t_mismatch, entry, src
    );
    println!(
        "\n--- Timeline t-25 to t+2 (prf_write, bypass, IQ entry {}, in_fire) ---\n",
        entry
    );

    let t_start = t_mismatch.saturating_sub(25);
    let t_end = t_mismatch + 2;

    for (i, &t) in time_table.iter().enumerate() {
        if t < t_start || t > t_end {
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

        let mut prf_writes: Vec<String> = Vec::new();
        for port in 0..6 {
            let v = get_val(&format!("iq.io_prf_write_{}_valid", port), &vals);
            let a = get_val(&format!("iq.io_prf_write_{}_bits_addr", port), &vals);
            if v.as_deref() == Some("1") {
                prf_writes.push(format!("P{}:{}", port, a.as_deref().unwrap_or("?")));
            }
        }
        let mut bypass: Vec<String> = Vec::new();
        for port in 0..6 {
            let v = get_val(&format!("iq.io_bypass_level1_{}_valid", port), &vals);
            let a = get_val(&format!("iq.io_bypass_level1_{}_bits_addr", port), &vals);
            if v.as_deref() == Some("1") {
                bypass.push(format!("B{}:{}", port, a.as_deref().unwrap_or("?")));
            }
        }
        let in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let in_ready = get_val("iq.io_in_ready", &vals).map_or(false, |v| v == "1");
        let in_fire = in_valid && in_ready;
        let in_p_rs2 = get_val("iq.io_in_bits_p_rs2", &vals)
            .or_else(|| get_val("iq.io_in_bits_r_p_rs2", &vals));

        let e_valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
        let e_p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
        let e_rs2_ready =
            get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals).map_or(false, |v| v == "1");
        let bank_2 = get_val("bank_ready_0_2", &vals).map_or(false, |v| v == "1");

        let prf_w = if prf_writes.is_empty() {
            ".".to_string()
        } else {
            prf_writes.join(" ")
        };
        let byp = if bypass.is_empty() {
            ".".to_string()
        } else {
            bypass.join(" ")
        };
        let mark = if t == t_mismatch { " <-- MISMATCH" } else { "" };
        println!(
            "t={:4} prf_write=[{}] bypass=[{}] in_fire={} in_p_rs2={:?} bank_2={} entry{}: valid={} p_rs2={:?} rs2_ready={}{}",
            t, prf_w, byp, in_fire, in_p_rs2, bank_2, entry, e_valid, e_p_rs2, e_rs2_ready, mark
        );
    }
    Ok(())
}
