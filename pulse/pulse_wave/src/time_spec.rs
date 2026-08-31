use std::ops::RangeInclusive;
use std::str::FromStr;

use serde::Serialize;
use serde::ser::SerializeStruct;

/// Serde-compatible newtype for `RangeInclusive<T>`.
///
/// A point is a degenerate range (`5..=5`).
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
    /// Expand to the list of individual ticks.
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
            // "-100" → 0..=100
            (dash, integer).map(|(_, to)| SerdeRange(0..=to)),
            // "~100" → last 100 (encoded as a sentinel tail range; the caller
            // resolves it against the trace's last tick/cycle)
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
    /// trace length. Returns `Some(n)` when this range is a `~N` sentinel.
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
        // `-100` → 0..=100 (from start).
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
        // `~0` is clamped to a 1-wide tail.
        let r: SerdeRange<u64> = "~0".parse().unwrap();
        assert_eq!(r.tail_n(), Some(1));
    }
}
