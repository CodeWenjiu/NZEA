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
