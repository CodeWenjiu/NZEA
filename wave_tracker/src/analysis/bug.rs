use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

/// Bug scan: find cycles where PR is both in FreeList (any buf slot) AND RMT table_1 (sp) = PR.
pub fn bug_scan_pr_in_both(
    wf: &mut Waveform,
    time_table: &[Time],
    sig_refs: &[(String, SignalRef)],
    start: Time,
    end: Time,
    pr_bin: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    if sig_refs.is_empty() {
        return Err("Could not find freeList.buf_* or rmt.table_1 signals".into());
    }
    if !sig_refs.iter().any(|(n, _)| n.contains("table_1")) {
        return Err("Could not find rmt.table_1".into());
    }

    let to_load: Vec<SignalRef> = sig_refs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    println!(
        "Bug scan: PR={} in FreeList buf AND RMT(sp)=table_1",
        pr_bin
    );
    println!("Cycles where both true:\n");

    for (i, &t) in time_table.iter().enumerate() {
        if t < start {
            continue;
        }
        if t > end {
            break;
        }
        let idx = i as u32;

        let mut table_1_val = String::new();
        let mut pr_in_buf = false;
        let mut buf_slots_with_pr: Vec<String> = Vec::new();

        for (name, sig_ref) in sig_refs.iter() {
            if let Some(sig) = wf.get_signal(*sig_ref) {
                if let Some(offset) = sig.get_offset(idx) {
                    let val = sig.get_value_at(&offset, 0).to_string();
                    let val_trim = val.trim();
                    if name.contains("table_1") {
                        table_1_val = val_trim.to_string();
                    } else if val_trim == pr_bin || val_trim.ends_with(pr_bin) {
                        pr_in_buf = true;
                        buf_slots_with_pr.push(name.clone());
                    }
                }
            }
        }

        let rmt_has_pr = table_1_val == pr_bin;

        if pr_in_buf && rmt_has_pr {
            println!(
                "t={} idx={} *** BUG: PR{} in FreeList AND table_1(sp)=PR{}",
                t, idx, pr_bin, pr_bin
            );
            println!("  rmt.table_1: {}", table_1_val);
            for slot in &buf_slots_with_pr {
                println!("  {}: {} (contains PR)", slot, pr_bin);
            }
        }
    }

    Ok(())
}
