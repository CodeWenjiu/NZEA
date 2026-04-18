use std::collections::HashMap;

use wellen::simple::Waveform;
use wellen::{SignalRef, Time};

#[derive(Clone)]
struct IndexedSig {
    s: usize,
    e: usize,
    name: String,
}

fn parse_owner_indices(name: &str, marker: &str) -> Option<(usize, usize)> {
    let tail = name.split(marker).nth(1)?;
    let mut parts = tail.split('_');
    let s = parts.next()?.parse::<usize>().ok()?;
    let e = parts.next()?.parse::<usize>().ok()?;
    Some((s, e))
}

fn parse_bin_u64(s: &str) -> Option<u64> {
    let t = s.trim();
    if t.is_empty() || t.chars().any(|c| c != '0' && c != '1') {
        return None;
    }
    u64::from_str_radix(t, 2).ok()
}

fn is_true(s: Option<&str>) -> bool {
    s == Some("1")
}

fn is_all_zero_bin(s: Option<&str>) -> bool {
    s.is_some_and(|v| !v.is_empty() && v.chars().all(|c| c == '0'))
}

/// Analyze FabricBus/Xbar deadlock near trace tail.
///
/// Focuses on:
/// - in-flight owner table saturation (`ownerValid_*_*`)
/// - req blocked while owner table is full
/// - slave response valid but `respMatchOH_* == 0` (unmatched by ID)
pub fn fabric_deadlock_tail(
    wf: &mut Waveform,
    time_table: &[Time],
    n: u64,
) -> Result<(), Box<dyn std::error::Error>> {
    if time_table.is_empty() {
        println!("Empty waveform.");
        return Ok(());
    }

    let total = time_table.len();
    let start_idx = if total as u64 > n {
        total - n as usize
    } else {
        0
    };
    let t_start = time_table[start_idx];
    let t_end = *time_table.last().unwrap_or(&t_start);

    let hierarchy = wf.hierarchy();
    let mut sigs: Vec<(String, SignalRef)> = Vec::new();
    for var in hierarchy.iter_vars() {
        let name = var.full_name(hierarchy);
        if name.contains("NzeaTile.fabric.io_in_0_req_")
            || name.contains("NzeaTile.fabric.io_in_0_resp_")
            || name.contains("NzeaTile.fabric.io_in_1_req_")
            || name.contains("NzeaTile.fabric.io_in_1_resp_")
            || name.contains("NzeaTile.fabric.io_out_")
            || name.contains("NzeaTile.fabric.ownerValid_")
            || name.contains("NzeaTile.fabric.ownerId_")
            || name.contains("NzeaTile.fabric.respMatchOH_")
            || name.contains("NzeaTile.fabric.flushFromMasters")
        {
            sigs.push((name, var.signal_ref()));
        }
    }

    if sigs.is_empty() {
        println!("No NzeaTile.fabric signals found in waveform.");
        return Ok(());
    }

    let owner_valid_sigs: Vec<IndexedSig> = sigs
        .iter()
        .filter_map(|(name, _)| {
            parse_owner_indices(name, "ownerValid_").map(|(s, e)| IndexedSig {
                s,
                e,
                name: name.clone(),
            })
        })
        .collect();
    let owner_id_sigs: Vec<IndexedSig> = sigs
        .iter()
        .filter_map(|(name, _)| {
            parse_owner_indices(name, "ownerId_").map(|(s, e)| IndexedSig {
                s,
                e,
                name: name.clone(),
            })
        })
        .collect();

    if owner_valid_sigs.is_empty() {
        println!("No ownerValid_*_* signals found under NzeaTile.fabric.");
        return Ok(());
    }

    let num_slaves = owner_valid_sigs.iter().map(|s| s.s).max().unwrap_or(0) + 1;
    let slots_per_slave = owner_valid_sigs.iter().map(|s| s.e).max().unwrap_or(0) + 1;

    let to_load: Vec<SignalRef> = sigs.iter().map(|(_, sr)| *sr).collect();
    wf.load_signals(&to_load);

    fn get_val<'a>(needle: &str, vals: &'a HashMap<&str, String>) -> Option<&'a str> {
        vals.get(needle).map(|s| s.as_str())
    }

    let mut first_unmatched_resp: Option<(Time, usize)> = None;
    let mut first_req_blocked_full: Option<(Time, usize)> = None;

    let mut last_owner_count = vec![0usize; num_slaves];
    let mut last_in0_req_valid = false;
    let mut last_in0_req_ready = false;
    let mut last_in0_req_id: Option<u64> = None;
    let mut last_in0_req_addr: Option<u64> = None;

    for (i, &t) in time_table.iter().enumerate() {
        if i < start_idx {
            continue;
        }

        let idx = i as u32;
        let mut vals: HashMap<&str, String> = HashMap::with_capacity(sigs.len());
        for (name, sig_ref) in &sigs {
            if let Some(sig) = wf.get_signal(*sig_ref)
                && let Some(offset) = sig.get_offset(idx)
            {
                vals.insert(name.as_str(), sig.get_value_at(&offset, 0).to_string());
            }
        }

        let mut owner_count = vec![0usize; num_slaves];
        for s in &owner_valid_sigs {
            if is_true(get_val(&s.name, &vals)) {
                owner_count[s.s] += 1;
            }
        }
        last_owner_count = owner_count.clone();

        let in0_req_valid = is_true(get_val("TOP.NzeaTile.fabric.io_in_0_req_valid", &vals));
        let in0_req_ready = is_true(get_val("TOP.NzeaTile.fabric.io_in_0_req_ready", &vals));
        let in0_req_id = get_val("TOP.NzeaTile.fabric.io_in_0_req_bits_id", &vals).and_then(parse_bin_u64);
        let in0_req_addr = get_val("TOP.NzeaTile.fabric.io_in_0_req_bits_addr", &vals).and_then(parse_bin_u64);
        last_in0_req_valid = in0_req_valid;
        last_in0_req_ready = in0_req_ready;
        last_in0_req_id = in0_req_id;
        last_in0_req_addr = in0_req_addr;

        if first_req_blocked_full.is_none() {
            for (s, &cnt) in owner_count.iter().enumerate() {
                if in0_req_valid && !in0_req_ready && cnt == slots_per_slave {
                    first_req_blocked_full = Some((t, s));
                    break;
                }
            }
        }

        if first_unmatched_resp.is_none() {
            for s in 0..num_slaves {
                let rv = format!("TOP.NzeaTile.fabric.io_out_{}_resp_valid", s);
                let match_oh = format!("TOP.NzeaTile.fabric.respMatchOH_{}", s);
                let resp_valid = is_true(get_val(&rv, &vals));
                let unmatched = is_all_zero_bin(get_val(&match_oh, &vals));
                if resp_valid && unmatched {
                    first_unmatched_resp = Some((t, s));
                    break;
                }
            }
        }
    }

    println!(
        "Fabric deadlock tail (t={}..{}, {} cycles), slaves={}, slots/slave={}",
        t_start,
        t_end,
        (t_end - t_start + 1),
        num_slaves,
        slots_per_slave
    );
    println!(
        "Last cycle: in0.req valid={} ready={} id={:?} addr={:?}",
        last_in0_req_valid,
        last_in0_req_ready,
        last_in0_req_id,
        last_in0_req_addr.map(|v| format!("0x{v:08x}"))
    );
    for (s, cnt) in last_owner_count.iter().enumerate() {
        println!("  ownerCount[slave{}] = {}/{}", s, cnt, slots_per_slave);
    }

    if let Some((t, s)) = first_unmatched_resp {
        println!(
            "First unmatched response: t={}, slave{} (resp_valid=1 but respMatchOH_{} is all zero)",
            t, s, s
        );
    } else {
        println!("No unmatched response found in selected tail window.");
    }

    if let Some((t, s)) = first_req_blocked_full {
        println!(
            "First req-blocked-while-full: t={}, slave{} (in0.req.valid=1 && in0.req.ready=0 && ownerCount full)",
            t, s
        );
    } else {
        println!("No req-blocked-while-full condition found in selected tail window.");
    }

    if num_slaves > 0 {
        let mut active_ids: Vec<u64> = owner_id_sigs
            .iter()
            .filter(|x| x.s == 0)
            .filter_map(|x| {
                let valid_name = format!("TOP.NzeaTile.fabric.ownerValid_{}_{}", x.s, x.e);
                let id_name = &x.name;
                // Re-read final values by direct query in loaded signal cache.
                let idx = (time_table.len() - 1) as u32;
                let valid = sigs
                    .iter()
                    .find(|(n, _)| n == &valid_name)
                    .and_then(|(_, sr)| wf.get_signal(*sr))
                    .and_then(|sig| sig.get_offset(idx).map(|off| sig.get_value_at(&off, 0).to_string()))
                    .unwrap_or_default();
                if valid != "1" {
                    return None;
                }
                sigs.iter()
                    .find(|(n, _)| n == id_name)
                    .and_then(|(_, sr)| wf.get_signal(*sr))
                    .and_then(|sig| sig.get_offset(idx).map(|off| sig.get_value_at(&off, 0).to_string()))
                    .and_then(|v| parse_bin_u64(&v))
            })
            .collect();
        active_ids.sort_unstable();
        active_ids.dedup();
        if !active_ids.is_empty() {
            println!("Active owner IDs on slave0 at last cycle: {:?}", active_ids);
        }
    }

    if let (Some((tu, su)), Some((tb, sb))) = (first_unmatched_resp, first_req_blocked_full)
        && su == sb && tu <= tb
    {
        println!();
        println!("Likely root cause:");
        println!("  slave{} responses become unmatched before/when queue saturates.", su);
        println!("  Xbar keeps outstanding entries (ownerValid) but cannot retire them,");
        println!("  then in0.req.ready stays low due full outstanding table.");
        println!("  Check response-ID preservation on the slave path.");
    }

    Ok(())
}
