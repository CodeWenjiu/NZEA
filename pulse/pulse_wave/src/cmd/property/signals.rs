/// Read a single-bit value from a signal at a given time index.
///
/// Semantics preserved from the original implementation: for a multi-bit
/// vector this returns the **most-significant bit** (`format!`-style string
/// rendering is MSB-first, so `chars().next()` read bit `width-1`). One-bit
/// signals return that bit directly.
pub(super) fn read_bit(sig: &wellen::Signal, tt_idx: u32) -> bool {
    sig.get_offset(tt_idx)
        .map(|off| {
            let v = sig.get_value_at(&off, 0);
            match v {
                wellen::SignalValueRef::BitVec(bv) => {
                    let msb = bv.width().saturating_sub(1);
                    bv.get_bit(msb).as_ascii() == '1'
                }
                // Non-bit-vector values (Event/String/Real) render as something
                // other than '1' in the old format! path, so treat as false.
                _ => false,
            }
        })
        .unwrap_or(false)
}

/// Read a signal value as an unsigned integer (for comparisons).
/// Returns `None` on unknown values (X/Z, real, string) or missing data.
pub(super) fn read_u64(sig: &wellen::Signal, tt_idx: u32) -> Option<u64> {
    let val = sig
        .get_offset(tt_idx)
        .map(|off| sig.get_value_at(&off, 0))?;
    u64::try_from(val).ok()
}
