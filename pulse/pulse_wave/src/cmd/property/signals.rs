/// Read a single-bit value from a signal at a given time index.
pub(super) fn read_bit(sig: &wellen::Signal, tt_idx: u32) -> bool {
    sig.get_offset(tt_idx)
        .map(|off| {
            let v = sig.get_value_at(&off, 0);
            let s = format!("{v}");
            s.chars().next().unwrap_or('0') == '1'
        })
        .unwrap_or(false)
}

/// Read a signal value as an unsigned integer (for comparisons).
/// Returns `None` on unknown values (X/Z, real, string) or missing data.
pub(super) fn read_u64(sig: &wellen::Signal, tt_idx: u32) -> Option<u64> {
    let val = sig.get_offset(tt_idx).map(|off| sig.get_value_at(&off, 0))?;
    u64::try_from(val).ok()
}
