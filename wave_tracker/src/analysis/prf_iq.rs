use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// PRF-IQ mismatch scan: find cycles where PRF has bank_ready=1 but IQ entry has rs1/rs2_ready=0.
/// Anomaly = PRF says ready, IQ says not ready (bypass persist should have updated IQ).
pub fn prf_iq_mismatch_scan(
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
            || name.contains("core.iq.entries_")
                && (name.contains("p_rs1")
                    || name.contains("p_rs2")
                    || name.contains("rs1_ready")
                    || name.contains("rs2_ready"))
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

    let mut found = 0u32;
    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
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

        for entry in 0..8 {
            let valid = get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
            if !valid {
                continue;
            }
            let p_rs1 = get_val(&format!("iq.entries_{}_p_rs1", entry), &vals);
            let p_rs2 = get_val(&format!("iq.entries_{}_p_rs2", entry), &vals);
            let rs1_ready = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                .map_or(false, |v| v == "1");
            let rs2_ready = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                .map_or(false, |v| v == "1");

            for (src, p_rs, iq_ready) in [("rs1", p_rs1, rs1_ready), ("rs2", p_rs2, rs2_ready)] {
                let Some(pr_bin) = p_rs else { continue };
                let pr = u32::from_str_radix(pr_bin.trim(), 2).unwrap_or(99);
                if pr == 0 {
                    continue;
                }
                if iq_ready {
                    continue;
                }
                let bank = (pr >> 4) as usize;
                let idx_bank = (pr & 0xF) as usize;
                let bank_suffix = format!("bank_ready_{}_{}", bank, idx_bank);
                let prf_ready = get_val(&bank_suffix, &vals).map_or(false, |v| v == "1");
                if prf_ready {
                    found += 1;
                    println!(
                        "t={} *** PRF-IQ MISMATCH: entry={} {} p_rs={} (PR{}) PRF ready=1 IQ ready=0",
                        t, entry, src, pr_bin, pr
                    );
                }
            }
        }
    }
    println!("\nTotal PRF-IQ mismatches found: {}", found);
    Ok(())
}
