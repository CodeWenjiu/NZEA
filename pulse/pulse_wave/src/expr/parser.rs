/// Event expression parser.
///
/// Uses winnow for primitives (name, integer), manual token matching
/// via `starts_with` for operators to keep the parser simple and correct.
use std::str::FromStr;

use winnow::ascii::digit1;
use winnow::error::ContextError;
use winnow::prelude::*;
use winnow::token::take_while;

use super::ast::{Expr, SequenceStep};
use crate::WaveError;

pub fn parse(input: &str) -> Result<Expr, WaveError> {
    let mut s = input.trim();
    let expr = expr(&mut s).map_err(|e| WaveError::Parse(e.to_string()))?;
    s = s.trim();
    if !s.is_empty() {
        return Err(WaveError::Parse(format!(
            "unexpected trailing input: '{}'",
            s
        )));
    }
    Ok(expr)
}

// ── low-level input helpers ────────────────────────────────

fn eat(input: &mut &str, token: &str) -> bool {
    let t = input.trim_start();
    if t.starts_with(token) {
        *input = &t[token.len()..];
        true
    } else {
        false
    }
}

fn eat_any(input: &mut &str, tokens: &[&str]) -> bool {
    let t = input.trim_start();
    for tok in tokens {
        if t.starts_with(tok) {
            *input = &t[tok.len()..];
            return true;
        }
    }
    false
}

fn eat_char(input: &mut &str, c: char) -> bool {
    let t = input.trim_start();
    if t.starts_with(c) {
        *input = &t[c.len_utf8()..];
        true
    } else {
        false
    }
}

fn name(input: &mut &str) -> Result<String, WaveError> {
    let s = input.trim_start();
    let mut s2 = s;
    let result: winnow::Result<&str, ContextError> =
        take_while(1.., |c: char| c.is_ascii_alphanumeric() || c == '_')
            .verify(|s: &str| {
                s.chars()
                    .next()
                    .map_or(false, |c| c.is_ascii_alphabetic() || c == '_')
            })
            .parse_next(&mut s2);
    match result {
        Ok(name) => {
            *input = s2;
            Ok(name.to_string())
        }
        Err(e) => Err(WaveError::Parse(format!("expected name: {}", e))),
    }
}

fn integer(input: &mut &str) -> Result<u32, WaveError> {
    let s = input.trim_start();
    let mut s2 = s;
    let result: winnow::Result<&str, ContextError> = digit1.parse_next(&mut s2);
    match result {
        Ok(digits) => {
            let n =
                u32::from_str(digits).map_err(|_| WaveError::Parse("integer overflow".into()))?;
            *input = s2;
            Ok(n)
        }
        Err(e) => Err(WaveError::Parse(format!("expected integer: {}", e))),
    }
}

// ── grammar ────────────────────────────────────────────────

fn expr(input: &mut &str) -> Result<Expr, WaveError> {
    implication(input)
}

/// implication = sequence ("|->" sequence)?
fn implication(input: &mut &str) -> Result<Expr, WaveError> {
    let left = sequence(input)?;
    if eat_any(input, &["|->", "|>"]) {
        let right = sequence(input)?;
        Ok(Expr::Implication(Box::new(left), Box::new(right)))
    } else {
        Ok(left)
    }
}

/// sequence = delay (">>" INT delay)*
fn sequence(input: &mut &str) -> Result<Expr, WaveError> {
    let first = delay(input)?;
    let mut steps = vec![SequenceStep {
        expr: Box::new(first),
        delay: 0,
    }];
    while eat(input, ">>") {
        let n = integer(input)?;
        let e = delay(input)?;
        steps.push(SequenceStep {
            expr: Box::new(e),
            delay: n,
        });
    }
    if steps.len() == 1 {
        let first = steps.into_iter().next().unwrap();
        Ok(*first.expr)
    } else {
        Ok(Expr::Sequence(steps))
    }
}

/// delay = term (op term)?
fn delay(input: &mut &str) -> Result<Expr, WaveError> {
    let left = term(input)?;

    // ->N term (longest match first, rewind on failure)
    let saved = *input;
    if eat(input, "->") {
        if let Ok(n) = integer(input) {
            let right = term(input)?;
            return Ok(Expr::FixedDelay(Box::new(left), n, Box::new(right)));
        }
        *input = saved;
    }

    if eat(input, "->") {
        let right = term(input)?;
        Ok(Expr::FirstAfter(Box::new(left), Box::new(right)))
    } else if eat(input, "~>") {
        let right = term(input)?;
        Ok(Expr::Overlapping(Box::new(left), Box::new(right)))
    } else if eat(input, "~~") {
        let right = term(input)?;
        Ok(Expr::Interval(Box::new(left), Box::new(right)))
    } else if eat(input, "--") {
        let n = integer(input)?;
        let right = term(input)?;
        Ok(Expr::Within(Box::new(left), n, Box::new(right)))
    } else {
        Ok(left)
    }
}

/// term = factor (("&&" | "||") factor)*
fn term(input: &mut &str) -> Result<Expr, WaveError> {
    let mut left = factor(input)?;
    loop {
        if eat(input, "&&") {
            let right = factor(input)?;
            left = Expr::And(Box::new(left), Box::new(right));
        } else if eat(input, "||") {
            let right = factor(input)?;
            left = Expr::Or(Box::new(left), Box::new(right));
        } else {
            break;
        }
    }
    Ok(left)
}

/// factor = "!"? atom
fn factor(input: &mut &str) -> Result<Expr, WaveError> {
    if eat_char(input, '!') {
        let inner = atom(input)?;
        Ok(Expr::Not(Box::new(inner)))
    } else {
        atom(input)
    }
}

/// atom = NAME "[" INT "]" | "(" expr ")" | NAME
fn atom(input: &mut &str) -> Result<Expr, WaveError> {
    if eat_char(input, '(') {
        let e = expr(input)?;
        if !eat_char(input, ')') {
            return Err(WaveError::Parse("expected ')'".into()));
        }
        return Ok(e);
    }

    let n = name(input)?;
    if eat_char(input, '[') {
        let cnt = integer(input)?;
        if !eat_char(input, ']') {
            return Err(WaveError::Parse("expected ']'".into()));
        }
        Ok(Expr::Repeat(Box::new(Expr::Signal(n)), cnt))
    } else {
        Ok(Expr::Signal(n))
    }
}

// ── tests ─────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Expr {
        parse(s).unwrap()
    }

    #[test]
    fn signal_name() {
        assert_eq!(p("foo"), Expr::Signal("foo".into()));
    }

    #[test]
    fn and_or() {
        assert_eq!(
            p("a && b || c"),
            Expr::Or(
                Box::new(Expr::And(
                    Box::new(Expr::Signal("a".into())),
                    Box::new(Expr::Signal("b".into())),
                )),
                Box::new(Expr::Signal("c".into())),
            )
        );
    }

    #[test]
    fn not() {
        assert_eq!(
            p("!a && b"),
            Expr::And(
                Box::new(Expr::Not(Box::new(Expr::Signal("a".into())))),
                Box::new(Expr::Signal("b".into())),
            )
        );
    }

    #[test]
    fn paren_grouping() {
        assert_eq!(
            p("a && (b || c)"),
            Expr::And(
                Box::new(Expr::Signal("a".into())),
                Box::new(Expr::Or(
                    Box::new(Expr::Signal("b".into())),
                    Box::new(Expr::Signal("c".into())),
                )),
            )
        );
    }

    #[test]
    fn first_after() {
        assert_eq!(
            p("miss -> resp"),
            Expr::FirstAfter(
                Box::new(Expr::Signal("miss".into())),
                Box::new(Expr::Signal("resp".into())),
            )
        );
    }

    #[test]
    fn fixed_delay() {
        assert_eq!(
            p("req ->3 grant"),
            Expr::FixedDelay(
                Box::new(Expr::Signal("req".into())),
                3,
                Box::new(Expr::Signal("grant".into())),
            )
        );
    }

    #[test]
    fn within() {
        assert_eq!(
            p("miss --5 resp"),
            Expr::Within(
                Box::new(Expr::Signal("miss".into())),
                5,
                Box::new(Expr::Signal("resp".into())),
            )
        );
    }

    #[test]
    fn overlapping() {
        assert_eq!(
            p("req ~> retry"),
            Expr::Overlapping(
                Box::new(Expr::Signal("req".into())),
                Box::new(Expr::Signal("retry".into())),
            )
        );
    }

    #[test]
    fn interval() {
        assert_eq!(
            p("stall ~~ done"),
            Expr::Interval(
                Box::new(Expr::Signal("stall".into())),
                Box::new(Expr::Signal("done".into())),
            )
        );
    }

    #[test]
    fn implication() {
        assert_eq!(
            p("grant |-> !req"),
            Expr::Implication(
                Box::new(Expr::Signal("grant".into())),
                Box::new(Expr::Not(Box::new(Expr::Signal("req".into())))),
            )
        );
    }

    #[test]
    fn sequence() {
        let expected = Expr::Sequence(vec![
            SequenceStep {
                expr: Box::new(Expr::Signal("s0".into())),
                delay: 0,
            },
            SequenceStep {
                expr: Box::new(Expr::Signal("s1".into())),
                delay: 1,
            },
            SequenceStep {
                expr: Box::new(Expr::Signal("s2".into())),
                delay: 2,
            },
        ]);
        assert_eq!(p("s0 >>1 s1 >>2 s2"), expected);
    }

    #[test]
    fn repetition() {
        assert_eq!(
            p("stall[5]"),
            Expr::Repeat(Box::new(Expr::Signal("stall".into())), 5)
        );
    }

    #[test]
    fn complex_delay_chain() {
        let expected = Expr::FixedDelay(
            Box::new(Expr::Signal("miss".into())),
            3,
            Box::new(Expr::And(
                Box::new(Expr::Signal("resp".into())),
                Box::new(Expr::Not(Box::new(Expr::Signal("err".into())))),
            )),
        );
        assert_eq!(p("miss ->3 resp && !err"), expected);
    }

    #[test]
    fn delay_with_spaces() {
        assert_eq!(
            p("a  ->  3  b"),
            Expr::FixedDelay(
                Box::new(Expr::Signal("a".into())),
                3,
                Box::new(Expr::Signal("b".into())),
            )
        );
    }

    #[test]
    fn invalid_syntax() {
        assert!(parse("a ->").is_err());
        assert!(parse("(a").is_err());
        assert!(parse("").is_err());
        assert!(parse("a ~>").is_err());
    }
}
