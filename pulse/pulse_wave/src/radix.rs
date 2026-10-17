//! Shared radix rendering for multi-bit signal values (`--radix` on `value`
//! and on `property`'s `--sample`).

use crate::WaveError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Radix {
    Bin,
    Hex,
    Dec,
    Oct,
}

impl Radix {
    pub(crate) fn from_str(s: &str) -> Result<Self, WaveError> {
        match s.to_ascii_lowercase().as_str() {
            "bin" => Ok(Radix::Bin),
            "hex" => Ok(Radix::Hex),
            "dec" => Ok(Radix::Dec),
            "oct" => Ok(Radix::Oct),
            other => Err(WaveError::Parse(format!(
                "invalid radix '{other}' (expected bin, hex, dec, oct)"
            ))),
        }
    }

    pub(crate) fn render(&self, width: usize, n: u64) -> String {
        match self {
            Radix::Bin => format!("{n:0width$b}", width = width.max(1)),
            Radix::Hex => {
                let nibbles = width.div_ceil(4).max(1);
                format!("{n:0width$x}", width = nibbles)
            }
            Radix::Dec => format!("{n}"),
            Radix::Oct => {
                let digits = width.div_ceil(3).max(1);
                format!("{n:0width$o}", width = digits)
            }
        }
    }
}
