//! Shared signal-value reads (bit, integer, and full classification).
//!
//! All commands sample signals by time-table index. The bit/u64 accessors and
//! the [`SignalValue`] classifier are the single source of truth for "what
//! `SignalValueRef` variants mean"; each command renders the classification
//! in its own shape (SVG levels, radix strings, log values) without
//! re-matching on wellen types.

/// Full classification of one signal sample at one time-table index.
///
/// - `Bit` — a 1-bit signal with a known value
/// - `Vector` — a multi-bit signal with a known value (width kept so renderers
///   can zero-pad)
/// - `X` — X/Z anywhere in the sample, carrying the raw binary string
///   (renderers decide whether to show it or a placeholder)
/// - `Missing` — no data at this tick
#[derive(Clone, Debug, PartialEq)]
pub(crate) enum SignalValue {
    Bit(bool),
    Vector { value: u64, width: usize },
    X(String),
    Missing,
}

/// Classify a signal sample. This is the **only** place that matches on
/// `wellen::SignalValueRef` variants for value reads.
pub(crate) fn classify(sig: &wellen::Signal, tt_idx: u32) -> SignalValue {
    let Some(off) = sig.get_offset(tt_idx) else {
        return SignalValue::Missing;
    };
    let v = sig.get_value_at(&off, 0);
    match v {
        wellen::SignalValueRef::BitVec(bv) if bv.width() == 1 => match bv.get_bit(0).as_ascii() {
            '1' => SignalValue::Bit(true),
            '0' => SignalValue::Bit(false),
            _ => SignalValue::X(bv.get_bit(0).as_ascii().to_string()),
        },
        other => {
            let width = other.width().unwrap_or(32) as usize;
            match u64::try_from(other) {
                Ok(n) => SignalValue::Vector { value: n, width },
                Err(_) => SignalValue::X(format!("{other}")),
            }
        }
    }
}

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
