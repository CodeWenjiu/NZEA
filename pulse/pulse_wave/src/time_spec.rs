use std::ops::RangeInclusive;
use std::str::FromStr;

use serde::Serialize;
use serde::ser::SerializeStruct;

/// Serde-compatible newtype for `RangeInclusive<T>`: a point serializes as a
/// bare number (a degenerate `5..=5`), a range as `{from, to}`.
#[derive(Debug, Clone)]
pub struct SerdeRange<T>(pub RangeInclusive<T>);

impl<T: Serialize + PartialEq> Serialize for SerdeRange<T> {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        if self.0.start() == self.0.end() {
            self.0.start().serialize(s)
        } else {
            let mut out = s.serialize_struct("Range", 2)?;
            out.serialize_field("from", self.0.start())?;
            out.serialize_field("to", self.0.end())?;
            out.end()
        }
    }
}

impl<T: std::fmt::Display + PartialEq> std::fmt::Display for SerdeRange<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.0.start() == self.0.end() {
            write!(f, "{}", self.0.start())
        } else {
            write!(f, "{}-{}", self.0.start(), self.0.end())
        }
    }
}

impl SerdeRange<u64> {
    pub fn resolve(&self) -> Vec<u64> {
        self.0.clone().collect()
    }
}

impl FromStr for SerdeRange<u64> {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        use winnow::ascii::digit1;
        use winnow::combinator::{alt, opt};
        use winnow::prelude::*;

        fn integer(input: &mut &str) -> winnow::Result<u64, winnow::error::ContextError> {
            digit1.try_map(|s: &str| s.parse()).parse_next(input)
        }

        fn dash<'a>(input: &mut &'a str) -> winnow::Result<&'a str, winnow::error::ContextError> {
            "-".parse_next(input)
        }

        fn tilde<'a>(input: &mut &'a str) -> winnow::Result<&'a str, winnow::error::ContextError> {
            "~".parse_next(input)
        }

        let mut input = s.trim();
        let spec = alt((
            (dash, integer).map(|(_, to)| SerdeRange(0..=to)),
            // "~100" → last 100, encoded as a sentinel tail range the caller
            // resolves against the trace's last tick/cycle
            (tilde, integer).map(|(_, n)| {
                let n = n.max(1);
                SerdeRange(u64::MAX - n + 1..=u64::MAX)
            }),
            // "100" | "100-" | "100-200"
            (integer, opt((dash, opt(integer)))).map(|(from, trailing)| match trailing {
                None => SerdeRange(from..=from),
                Some((_, None)) => SerdeRange(from..=u64::MAX),
                Some((_, Some(to))) => SerdeRange(from..=to),
            }),
        ))
        .parse_next(&mut input)
        .map_err(|e| format!("{e}"))?;
        input = input.trim();
        if !input.is_empty() {
            return Err(format!("unexpected trailing input: '{input}'"));
        }
        Ok(spec)
    }
}

impl SerdeRange<u64> {
    /// Sentinel encoding for the `~N` "last N" form: a tail range
    /// `[u64::MAX - N + 1, u64::MAX]` that callers resolve against the actual
    /// trace length.
    ///
    /// The sentinel start is always in the top half of the u64 space, which
    /// distinguishes it from an open-ended `N-` range (`N..=u64::MAX` with a
    /// small `N`), whose start is a normal address/tick value.
    pub fn tail_n(&self) -> Option<u64> {
        let start = *self.0.start();
        let end = *self.0.end();
        if end == u64::MAX && start > u64::MAX / 2 {
            Some(u64::MAX - start + 1)
        } else {
            None
        }
    }
}

/// One `value --at` argument after parsing: a tick/cycle/absolute-time range,
/// or a `match(EXPR)` event-expression time source.
///
/// Bare numbers are ticks (legacy behavior), `Nc` the Nth cycle
/// (posedge-clock count), `Nt` an explicit tick, `Nns` an absolute time in
/// nanoseconds (converted via the waveform timescale). Unit suffixes accept a
/// single point only; ranges stay in ticks.
#[derive(Debug, Clone)]
pub(crate) enum AtSpec {
    Ticks(SerdeRange<u64>),
    Cycles(SerdeRange<u64>),
    AbsNs(SerdeRange<u64>),
    /// `match(EXPR)`: every tick where the event expression fires.
    Match(String),
}

impl AtSpec {
    /// Parse one `--at` token (a user-supplied piece, already comma-split by
    /// the caller; `match(...)` arguments may contain commas, so that form is
    /// never split).
    pub(crate) fn from_str(s: &str) -> Result<Self, String> {
        let s = s.trim();
        if s.is_empty() {
            return Err("empty --at value".into());
        }
        if let Some(inner) = s.strip_prefix("match(") {
            let inner = inner
                .strip_suffix(')')
                .ok_or_else(|| format!("match(...) missing closing paren: '{s}'"))?;
            if inner.trim().is_empty() {
                return Err("--at match() needs an event expression".into());
            }
            return Ok(AtSpec::Match(inner.to_string()));
        }
        let (num, unit) = split_unit(s)?;
        let range: SerdeRange<u64> = num.parse()?;
        match unit {
            "" => Ok(AtSpec::Ticks(range)),
            "t" => Ok(AtSpec::Ticks(range)),
            "c" => {
                if *range.0.start() != *range.0.end() {
                    return Err(
                        "--at Nc accepts a single cycle point (ranges stay in ticks)".into(),
                    );
                }
                Ok(AtSpec::Cycles(range))
            }
            "ns" => {
                if *range.0.start() != *range.0.end() {
                    return Err(
                        "--at Nns accepts a single time point (ranges stay in ticks)".into(),
                    );
                }
                Ok(AtSpec::AbsNs(range))
            }
            other => Err(format!(
                "unknown --at unit '{other}' (expected c, t, ns, or bare ticks)"
            )),
        }
    }
}

/// Split `123c` / `45ns` / `67` into the numeric part and the unit suffix.
/// `-`/`~` prefixes and range forms (`100-200`) fall through as bare (`""`
/// unit) so `SerdeRange` parses them with its full syntax.
fn split_unit(s: &str) -> Result<(&str, &str), String> {
    let first = s.chars().next().unwrap_or(' ');
    if !first.is_ascii_digit() {
        return Ok((s, ""));
    }
    let digits: usize = s
        .char_indices()
        .find(|(_, c)| !c.is_ascii_digit())
        .map_or(s.len(), |(i, _)| i);
    let (num, unit) = s.split_at(digits);
    match unit {
        "" | "c" | "t" | "ns" => Ok((num, unit)),
        _ => Ok((s, "")),
    }
}

/// Convert an absolute duration in nanoseconds to waveform ticks, using the
/// dump's timescale (`factor` × `unit`). Rounds down to the nearest tick;
/// unknown units fall back to picoseconds (the common dump resolution).
pub(crate) fn ns_to_ticks(ns: u64, factor: u64, unit: wellen::TimescaleUnit) -> u64 {
    use wellen::TimescaleUnit::*;
    let unit_fs: u128 = match unit {
        Seconds => 1_000_000_000_000_000,
        MilliSeconds => 1_000_000_000_000,
        MicroSeconds => 1_000_000_000,
        NanoSeconds => 1_000_000,
        PicoSeconds => 1_000,
        FemtoSeconds => 1,
        _ => 1_000,
    };
    let fs = ns as u128 * 1_000_000;
    (fs / u128::max(factor as u128, 1).saturating_mul(unit_fs)) as u64
}

#[cfg(test)]
mod tests {
    use super::SerdeRange;

    #[test]
    fn parses_point_and_range() {
        let p: SerdeRange<u64> = "100".parse().unwrap();
        assert_eq!((*p.0.start(), *p.0.end()), (100, 100));

        let r: SerdeRange<u64> = "100-200".parse().unwrap();
        assert_eq!((*r.0.start(), *r.0.end()), (100, 200));
    }

    #[test]
    fn open_ended_range_is_not_tail() {
        // `100-` is an open-ended range (clamped to the last tick by callers),
        // NOT a `~N` tail sentinel — its start is a normal value.
        let r: SerdeRange<u64> = "100-".parse().unwrap();
        assert_eq!(*r.0.start(), 100);
        assert_eq!(*r.0.end(), u64::MAX);
        assert_eq!(r.tail_n(), None);
    }

    #[test]
    fn from_start_is_not_tail() {
        let r: SerdeRange<u64> = "-100".parse().unwrap();
        assert_eq!((*r.0.start(), *r.0.end()), (0, 100));
        assert_eq!(r.tail_n(), None);
    }

    #[test]
    fn tail_sentinel_roundtrips() {
        let r: SerdeRange<u64> = "~100".parse().unwrap();
        assert_eq!(r.tail_n(), Some(100));
        assert_eq!(*r.0.end(), u64::MAX);

        let r: SerdeRange<u64> = "~1".parse().unwrap();
        assert_eq!(r.tail_n(), Some(1));
    }

    #[test]
    fn tail_n_at_least_one() {
        let r: SerdeRange<u64> = "~0".parse().unwrap();
        assert_eq!(r.tail_n(), Some(1));
    }

    use super::AtSpec;

    #[test]
    fn at_bare_forms_stay_ticks() {
        let AtSpec::Ticks(r) = AtSpec::from_str("100").unwrap() else {
            panic!("bare point should be Ticks")
        };
        assert_eq!((*r.0.start(), *r.0.end()), (100, 100));
        let AtSpec::Ticks(r) = AtSpec::from_str("-100").unwrap() else {
            panic!("-N should be Ticks")
        };
        assert_eq!((*r.0.start(), *r.0.end()), (0, 100));
        let AtSpec::Ticks(r) = AtSpec::from_str("100-200").unwrap() else {
            panic!("range should be Ticks")
        };
        assert_eq!((*r.0.start(), *r.0.end()), (100, 200));
        let AtSpec::Ticks(r) = AtSpec::from_str("~50").unwrap() else {
            panic!("~N should be Ticks")
        };
        assert_eq!(r.tail_n(), Some(50));
        // Explicit tick suffix is the same domain.
        let AtSpec::Ticks(r) = AtSpec::from_str("100t").unwrap() else {
            panic!("Nt should be Ticks")
        };
        assert_eq!((*r.0.start(), *r.0.end()), (100, 100));
    }

    #[test]
    fn at_unit_suffixes() {
        let AtSpec::Cycles(r) = AtSpec::from_str("42c").unwrap() else {
            panic!("Nc should be Cycles")
        };
        assert_eq!(*r.0.start(), 42);
        let AtSpec::AbsNs(r) = AtSpec::from_str("100ns").unwrap() else {
            panic!("Nns should be AbsNs")
        };
        assert_eq!(*r.0.start(), 100);
        assert!(AtSpec::from_str("1c-2c").is_err());
        assert!(AtSpec::from_str("5xc").is_err());
    }

    #[test]
    fn at_match_form() {
        let AtSpec::Match(e) = AtSpec::from_str("match(commit_fire && rd_valid)").unwrap() else {
            panic!("match(...) should be Match")
        };
        assert_eq!(e, "commit_fire && rd_valid");
        // Commas inside the expression are fine (never comma-split).
        let AtSpec::Match(e) = AtSpec::from_str("match(change(a), b)").unwrap() else {
            panic!("match(...) should be Match")
        };
        assert_eq!(e, "change(a), b");
        assert!(AtSpec::from_str("match(").is_err());
        assert!(AtSpec::from_str("match()").is_err());
    }

    #[test]
    fn ns_to_ticks_conversion() {
        use wellen::TimescaleUnit::*;
        // 1 ps dump: 100 ns = 100_000 ticks.
        assert_eq!(super::ns_to_ticks(100, 1, PicoSeconds), 100_000);
        // 1 ns dump: 100 ns = 100 ticks.
        assert_eq!(super::ns_to_ticks(100, 1, NanoSeconds), 100);
        // 10 ns dump: 100 ns = 10 ticks.
        assert_eq!(super::ns_to_ticks(100, 10, NanoSeconds), 10);
        // 1 us dump: 50 ns = 0 ticks (below resolution).
        assert_eq!(super::ns_to_ticks(50, 1, MicroSeconds), 0);
    }
}
