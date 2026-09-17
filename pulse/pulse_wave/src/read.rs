//! Shared signal-value reads (bit and unsigned-integer views).
//!
//! All commands sample signals by time-table index; the bit/u64 accessors
//! used to be duplicated between `property` and `plot`. Both are cheap and
//! pure — keep them here so every consumer agrees on the semantics (MSB for
//! bit reads, `None` for X/Z/real/string values).

/// Read a single-bit value from a signal at a given time index.
///
/// For a multi-bit vector this returns the **most-significant bit** (`format!`
/// renders MSB-first). One-bit signals return that bit directly.
pub(crate) fn read_bit(sig: &wellen::Signal, tt_idx: u32) -> bool {
    sig.get_offset(tt_idx)
        .map(|off| {
            let v = sig.get_value_at(&off, 0);
            match v {
                wellen::SignalValueRef::BitVec(bv) => {
                    let msb = bv.width().saturating_sub(1);
                    bv.get_bit(msb).as_ascii() == '1'
                }
                // Non-bit-vector values (Event/String/Real) are never '1'.
                _ => false,
            }
        })
        .unwrap_or(false)
}

/// Read a signal value as an unsigned integer (for comparisons/samples).
/// Returns `None` on unknown values (X/Z, real, string) or missing data.
pub(crate) fn read_u64(sig: &wellen::Signal, tt_idx: u32) -> Option<u64> {
    let val = sig
        .get_offset(tt_idx)
        .map(|off| sig.get_value_at(&off, 0))?;
    u64::try_from(val).ok()
}
