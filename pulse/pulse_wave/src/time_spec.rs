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

        let mut input = s.trim();
        let spec = alt((
            // "-100" → 0..=100
            (dash, integer).map(|(_, to)| SerdeRange(0..=to)),
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
