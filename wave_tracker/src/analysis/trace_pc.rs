use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Trace instruction by PC through IQ/issue/BRU: find why it silently ends without entering BRU.
pub fn trace_pc_timeline(
    wf: &mut Waveform,
    time_table: &[Time],
    pc_bin: &str,
    hex_pc: &str,
    start: Time,
    end: Time,
) -> Result<(), Box<dyn std::error::Error>> {
    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if (name.contains("iq.entries_")
            && (name.contains("pc")
                || name.contains("valids_")
                || name.contains("rs1_ready")
                || name.contains("rs2_ready")
                || name.contains("fu_type")))
            || (name.contains("iq.io_in") && (name.contains("valid") || name.contains("pc")))
            || (name.contains("io_issuePorts_")
                && (name.contains("valid") || name.contains("ready") || name.contains("bits_pc")))
            || (name.contains("iq.count") || name.contains("iq.full"))
            || (name.contains("iq.io_flush") || name.contains("flush"))
            || (name.contains("orderedPorts") && name.contains("ready"))
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

    let matches_pc = |v: Option<&String>| v.as_deref().map(|s| s.trim()) == Some(pc_bin);

    // FuType: 0=ALU, 1=BRU, 2=LSU, 3=MUL, 4=DIV, 5=SYSU
    let fu_type_name = |ft: Option<&String>| -> String {
        ft.and_then(|s| u32::from_str_radix(s.trim(), 2).ok())
            .map(|n| match n {
                0 => "ALU",
                1 => "BRU",
                2 => "LSU",
                3 => "MUL",
                4 => "DIV",
                5 => "SYSU",
                _ => "?",
            })
            .unwrap_or_else(|| "?")
            .to_string()
    };

    println!(
        "Tracing PC=0x{} ({}) through IQ/BRU, t={}..{}\n",
        hex_pc, pc_bin, start, end
    );
    println!(
        "{:>6} | {:12} | {:8} | {:4} {:4} {:4} | {:4} | {:12} {:12} | {:6} {:6}",
        "t", "IQ_in", "IQ_ent", "valid", "rs1", "rs2", "fu", "ISSUE", "BRU", "count", "flush"
    );
    println!("{}", "-".repeat(100));

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

        let iq_in_valid = get_val("iq.io_in_valid", &vals).map_or(false, |v| v == "1");
        let iq_in_pc = get_val("iq.io_in_bits_pc", &vals);
        let iq_in = iq_in_valid && matches_pc(iq_in_pc.as_ref());

        let mut iq_ent: Option<(usize, bool, bool, bool, String)> = None;
        for entry in 0..8 {
            let epc = get_val(&format!("iq.entries_{}_pc", entry), &vals);
            if matches_pc(epc.as_ref()) {
                let valid =
                    get_val(&format!("iq.valids_{}", entry), &vals).map_or(false, |v| v == "1");
                let r1 = get_val(&format!("iq.entries_{}_rs1_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
                let r2 = get_val(&format!("iq.entries_{}_rs2_ready", entry), &vals)
                    .map_or(false, |v| v == "1");
                let ft = get_val(&format!("iq.entries_{}_fu_type", entry), &vals);
                let ft_str = fu_type_name(ft.as_ref());
                if valid {
                    iq_ent = Some((entry, valid, r1, r2, ft_str));
                    break;
                }
                if iq_ent.is_none() {
                    iq_ent = Some((entry, valid, r1, r2, ft_str));
                }
            }
        }

        let mut issue_port = None;
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
            let pc = get_val(&format!("io_issuePorts_{}_bits_pc", port), &vals);
            if v && matches_pc(pc.as_ref()) {
                issue_port = Some(name.to_string());
                break;
            }
        }

        let bru_valid = get_val("io_issuePorts_bru_valid", &vals).map_or(false, |v| v == "1");
        let bru_ready = get_val("io_issuePorts_bru_ready", &vals).map_or(false, |v| v == "1");
        let bru_pc = get_val("io_issuePorts_bru_bits_pc", &vals);
        let bru_has_pc = bru_valid && matches_pc(bru_pc.as_ref());

        let count = get_val("iq.count", &vals)
            .as_deref()
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "?".into());
        let flush = get_val("iq.io_flush", &vals).map_or(false, |v| v == "1");

        let iq_in_str = if iq_in { "IN" } else { "-" };
        let (ent_str, valid_str, r1_str, r2_str, ft_str) = match &iq_ent {
            Some((e, v, r1, r2, ft)) => (
                format!("[{}]", e),
                if *v { "1" } else { "0" },
                if *r1 { "1" } else { "0" },
                if *r2 { "1" } else { "0" },
                ft.clone(),
            ),
            None => ("-".into(), "-".into(), "-".into(), "-".into(), "-".into()),
        };
        let issue_str = issue_port.as_deref().unwrap_or("-");
        let bru_str = if bru_has_pc {
            format!(
                "V={} R={}",
                if bru_valid { "1" } else { "0" },
                if bru_ready { "1" } else { "0" }
            )
        } else {
            "-".into()
        };

        if iq_in || iq_ent.is_some() || issue_port.is_some() || bru_has_pc {
            println!(
                "{:>6} | {:12} | {:8} | {:4} {:4} {:4} | {:4} | {:12} {:12} | {:6} {:6}",
                t,
                iq_in_str,
                ent_str,
                valid_str,
                r1_str,
                r2_str,
                ft_str,
                issue_str,
                bru_str,
                count,
                if flush { "1" } else { "0" }
            );
        }
    }
    println!("\nNote: valid=0 with matching PC may mean entry was dequeued (issued) or flushed.");
    println!("      fu: FuType (BRU=1 for branch). If fu!=BRU, branch was mis-decoded.");
    println!("      BRU_ready: only div_ready may exist in waveform; BRU ready often inlined.");
    Ok(())
}
