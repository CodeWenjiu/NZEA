/// Event expression parser (winnow combinators).
use winnow::ascii::{digit1, hex_digit1, multispace0};
use winnow::combinator::{alt, not, terminated};
use winnow::error::ContextError;
use winnow::prelude::*;
use winnow::token::{literal as lit, one_of, take_while};

use super::ast::{CmpOp, Expr, SequenceStep};
use crate::WaveError;

type PResult<T> = winnow::Result<T, ContextError>;

pub(crate) fn parse(input: &str) -> Result<Expr, WaveError> {
    let original = input;
    let mut s = input;
    // On failure the combinators restore `s` to the failing position, so the
    // pointer delta yields the exact offset of the error.
    let expr = expr(&mut s).map_err(|e| {
        let off = s.as_ptr() as usize - original.as_ptr() as usize;
        locate(original, off, e.to_string())
    })?;
    s = s.trim_start();
    if !s.is_empty() {
        let off = s.as_ptr() as usize - original.as_ptr() as usize;
        return Err(locate(
            original,
            off,
            format!("unexpected trailing input: '{s}'"),
        ));
    }
    Ok(expr)
}

/// Attach a line/column position to a parse error.
fn locate(original: &str, off: usize, msg: String) -> WaveError {
    let (line, col) = line_col(original, off);
    WaveError::Parse(format!("at line {line}, column {col}: {msg}"))
}

fn line_col(input: &str, off: usize) -> (usize, usize) {
    let off = off.min(input.len());
    let before = &input[..off];
    let line = before.bytes().filter(|&b| b == b'\n').count() + 1;
    let col = off - before.rfind('\n').map_or(0, |i| i + 1) + 1;
    (line, col)
}

// ── low-level input helpers ────────────────────────────────

/// A literal token with optional leading whitespace.
/// (Explicit lifetime: elided `&str` in `impl Trait` is unstable on this
/// nightly; clippy's elision suggestion does not apply.)
#[allow(clippy::needless_lifetimes)]
fn ws_tag<'a>(token: &'a str) -> impl Parser<&'a str, &'a str, ContextError> {
    preceded_by_ws(lit(token))
}

#[allow(clippy::needless_lifetimes)]
fn preceded_by_ws<'a>(
    p: impl Parser<&'a str, &'a str, ContextError>,
) -> impl Parser<&'a str, &'a str, ContextError> {
    (multispace0, p).map(|(_, t)| t)
}

fn name(input: &mut &str) -> PResult<String> {
    let s = input.trim_start();
    let mut s2 = s;
    // Allow dots for namespaced references like "Core.instruction_fetch"
    let result = take_while(1.., |c: char| c.is_ascii_alphanumeric() || c == '_' || c == '.')
        .verify(|s: &str| {
            s.chars()
                .next()
                .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        })
        .parse_next(&mut s2);
    match result {
        Ok(name) => {
            *input = s2;
            Ok(name.to_string())
        }
        Err(e) => Err(e),
    }
}

fn integer(input: &mut &str) -> PResult<u32> {
    let s = input.trim_start();
    let mut s2 = s;
    // A number running into identifier characters belongs to a literal or
    // name ("0x...", "3abc"), so the caller can fall back instead of
    // mis-splitting the token.
    let n = terminated(
        digit1.try_map(|d: &str| d.parse::<u32>()),
        not(one_of(|c: char| c.is_ascii_alphanumeric() || c == '_' || c == '.')),
    )
    .parse_next(&mut s2)?;
    *input = s2;
    Ok(n)
}

/// Integer literal: decimal digits, or `0x`/`0X` hex digits.
fn literal(input: &mut &str) -> PResult<Option<u64>> {
    let s = input.trim_start();
    if s.starts_with("0x") || s.starts_with("0X") {
        // "0x" must be followed by at least one hex digit.
        let mut s2 = &s[2..];
        let v = hex_digit1
            .try_map(|d: &str| u64::from_str_radix(d, 16))
            .parse_next(&mut s2)?;
        *input = s2;
        return Ok(Some(v));
    }
    if !s.starts_with(|c: char| c.is_ascii_digit()) {
        return Ok(None);
    }
    let mut s2 = s;
    let v = digit1
        .try_map(|d: &str| d.parse::<u64>())
        .parse_next(&mut s2)?;
    *input = s2;
    Ok(Some(v))
}

// ── grammar ────────────────────────────────────────────────

/// event = implication
fn expr(input: &mut &str) -> PResult<Expr> {
    implication(input)
}

/// implication = sequence (("|->" | "|>") sequence)?
fn implication(input: &mut &str) -> PResult<Expr> {
    let left = sequence(input)?;
    if ws_tag("|->").parse_next(input).is_ok() || ws_tag("|>").parse_next(input).is_ok() {
        let right = sequence(input)?;
        Ok(Expr::Implication(Box::new(left), Box::new(right)))
    } else {
        Ok(left)
    }
}

/// sequence = delay (">>" INT delay)*
fn sequence(input: &mut &str) -> PResult<Expr> {
    let first = delay(input)?;
    let mut steps = vec![SequenceStep {
        expr: Box::new(first),
        delay: 0,
    }];
    while ws_tag(">>").parse_next(input).is_ok() {
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

/// delay = term (("->" INT)? term | "~>" term | "~~" term | "--" INT term)?
fn delay(input: &mut &str) -> PResult<Expr> {
    let left = term(input)?;

    // ->N term (longest match first; on non-integer fall back to -> term)
    if ws_tag("->").parse_next(input).is_ok() {
        if let Ok(n) = integer(input) {
            let right = term(input)?;
            return Ok(Expr::FixedDelay(Box::new(left), n, Box::new(right)));
        }
        let right = term(input)?;
        return Ok(Expr::FirstAfter(Box::new(left), Box::new(right)));
    }

    if ws_tag("~>").parse_next(input).is_ok() {
        let right = term(input)?;
        Ok(Expr::Overlapping(Box::new(left), Box::new(right)))
    } else if ws_tag("~~").parse_next(input).is_ok() {
        let right = term(input)?;
        Ok(Expr::Interval(Box::new(left), Box::new(right)))
    } else if ws_tag("--").parse_next(input).is_ok() {
        let n = integer(input)?;
        let right = term(input)?;
        Ok(Expr::Within(Box::new(left), n, Box::new(right)))
    } else {
        Ok(left)
    }
}

/// term = comparison (("&&" | "||") comparison)*
fn term(input: &mut &str) -> PResult<Expr> {
    let mut left = comparison(input)?;
    loop {
        if ws_tag("&&").parse_next(input).is_ok() {
            let right = comparison(input)?;
            left = Expr::And(Box::new(left), Box::new(right));
        } else if ws_tag("||").parse_next(input).is_ok() {
            let right = comparison(input)?;
            left = Expr::Or(Box::new(left), Box::new(right));
        } else {
            break;
        }
    }
    Ok(left)
}

/// comparison = factor (("==" | "!=" | ">=" | "<=" | ">" | "<") factor)?
fn comparison(input: &mut &str) -> PResult<Expr> {
    let left = factor(input)?;
    if let Some(op) = cmp_op(input)? {
        let right = factor(input)?;
        Ok(Expr::Cmp(Box::new(left), op, Box::new(right)))
    } else {
        Ok(left)
    }
}

/// Comparison operator, or `None` if the input does not start with one.
/// A bare `>` is a comparison, but `>>` opens a sequence step.
fn cmp_op(input: &mut &str) -> PResult<Option<CmpOp>> {
    if input.trim_start().starts_with(">>") {
        return Ok(None);
    }
    let op = alt((
        ws_tag("==").value(CmpOp::Eq),
        ws_tag("!=").value(CmpOp::Ne),
        ws_tag(">=").value(CmpOp::Ge),
        ws_tag("<=").value(CmpOp::Le),
        ws_tag(">").value(CmpOp::Gt),
        ws_tag("<").value(CmpOp::Lt),
    ))
    .parse_next(input);
    match op {
        Ok(op) => Ok(Some(op)),
        Err(_) => Ok(None),
    }
}

/// factor = "!"* atom
fn factor(input: &mut &str) -> PResult<Expr> {
    let mut nots = 0;
    while ws_tag("!").parse_next(input).is_ok() {
        nots += 1;
    }
    let mut inner = atom(input)?;
    for _ in 0..nots {
        inner = Expr::Not(Box::new(inner));
    }
    Ok(inner)
}

/// atom = "(" event ")" | INT | NAME ("[" INT "]")? | NAME "(" [event ("," event)*] ")"
fn atom(input: &mut &str) -> PResult<Expr> {
    if ws_tag("(").parse_next(input).is_ok() {
        let e = expr(input)?;
        ws_tag(")").parse_next(input)?;
        return Ok(e);
    }

    if let Some(v) = literal(input)? {
        return Ok(Expr::Const(v));
    }

    let n = name(input)?;
    if ws_tag("[").parse_next(input).is_ok() {
        let cnt = integer(input)?;
        ws_tag("]").parse_next(input)?;
        return Ok(Expr::Repeat(Box::new(Expr::Signal(n)), cnt));
    }
    if !n.contains('.') && ws_tag("(").parse_next(input).is_ok() {
        // Function call; dotted names are namespace references, not calls.
        let mut args = Vec::new();
        if !ws_tag(")").parse_next(input).is_ok() {
            loop {
                args.push(expr(input)?);
                if !ws_tag(",").parse_next(input).is_ok() {
                    ws_tag(")").parse_next(input)?;
                    break;
                }
            }
        }
        return Ok(Expr::Call(n, args));
    }
    Ok(Expr::Signal(n))
}

// ── tests ─────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::collection::vec;
    use proptest::prelude::*;

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
    fn double_not() {
        assert_eq!(
            p("!!a"),
            Expr::Not(Box::new(Expr::Not(Box::new(Expr::Signal("a".into())))))
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
    fn comparison() {
        assert_eq!(
            p("addr >= 0x80000000 && addr < 0x88000000"),
            Expr::And(
                Box::new(Expr::Cmp(
                    Box::new(Expr::Signal("addr".into())),
                    CmpOp::Ge,
                    Box::new(Expr::Const(0x8000_0000)),
                )),
                Box::new(Expr::Cmp(
                    Box::new(Expr::Signal("addr".into())),
                    CmpOp::Lt,
                    Box::new(Expr::Const(0x8800_0000)),
                )),
            )
        );
    }

    #[test]
    fn comparison_all_ops() {
        for (s, op) in [
            ("a == 1", CmpOp::Eq),
            ("a != 1", CmpOp::Ne),
            ("a > 1", CmpOp::Gt),
            ("a < 1", CmpOp::Lt),
            ("a >= 1", CmpOp::Ge),
            ("a <= 1", CmpOp::Le),
        ] {
            assert_eq!(
                p(s),
                Expr::Cmp(
                    Box::new(Expr::Signal("a".into())),
                    op,
                    Box::new(Expr::Const(1))
                ),
                "{s}"
            );
        }
    }

    #[test]
    fn comparison_precedence() {
        // Comparison binds tighter than &&, so `a == b && c` is `(a == b) && c`.
        assert_eq!(
            p("a == b && c"),
            Expr::And(
                Box::new(Expr::Cmp(
                    Box::new(Expr::Signal("a".into())),
                    CmpOp::Eq,
                    Box::new(Expr::Signal("b".into())),
                )),
                Box::new(Expr::Signal("c".into())),
            )
        );
        // `->` is a temporal operator, not a comparison.
        assert_eq!(
            p("a -> b"),
            Expr::FirstAfter(
                Box::new(Expr::Signal("a".into())),
                Box::new(Expr::Signal("b".into())),
            )
        );
    }

    #[test]
    fn decimal_and_hex_literals() {
        assert_eq!(p("42"), Expr::Const(42));
        assert_eq!(p("0xdeadbeef"), Expr::Const(0xdead_beef));
        assert_eq!(p("0Xff"), Expr::Const(255));
    }

    #[test]
    fn literal_in_comparison_chain() {
        assert_eq!(
            p("a > 5 && b > 3"),
            Expr::And(
                Box::new(Expr::Cmp(
                    Box::new(Expr::Signal("a".into())),
                    CmpOp::Gt,
                    Box::new(Expr::Const(5)),
                )),
                Box::new(Expr::Cmp(
                    Box::new(Expr::Signal("b".into())),
                    CmpOp::Gt,
                    Box::new(Expr::Const(3)),
                )),
            )
        );
    }

    #[test]
    fn function_call() {
        assert_eq!(
            p("prev(io_req_valid)"),
            Expr::Call(
                "prev".into(),
                vec![Expr::Signal("io_req_valid".into())]
            )
        );
        assert_eq!(
            p("prev(a, 2)"),
            Expr::Call("prev".into(), vec![Expr::Signal("a".into()), Expr::Const(2)])
        );
        assert_eq!(p("foo()"), Expr::Call("foo".into(), vec![]));
    }

    #[test]
    fn function_call_composition() {
        // Calls compose with boolean and temporal operators, and arguments
        // may be arbitrary expressions.
        assert_eq!(
            p("rise(a) && b"),
            Expr::And(
                Box::new(Expr::Call("rise".into(), vec![Expr::Signal("a".into())])),
                Box::new(Expr::Signal("b".into())),
            )
        );
        assert_eq!(
            p("f(a -> b)"),
            Expr::Call(
                "f".into(),
                vec![Expr::FirstAfter(
                    Box::new(Expr::Signal("a".into())),
                    Box::new(Expr::Signal("b".into())),
                )],
            )
        );
        assert_eq!(
            p("g(a, b, c)"),
            Expr::Call(
                "g".into(),
                vec![
                    Expr::Signal("a".into()),
                    Expr::Signal("b".into()),
                    Expr::Signal("c".into()),
                ],
            )
        );
        // Dotted names are namespace references, not calls.
        assert_eq!(p("Cache.miss"), Expr::Signal("Cache.miss".into()));
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

    #[test]
    fn error_location() {
        let err = parse("a &&\n!").unwrap_err().to_string();
        assert!(err.contains("line 2"), "got: {err}");

        let err = parse("a ->").unwrap_err().to_string();
        assert!(err.contains("column"), "got: {err}");

        let err = parse("a && b )").unwrap_err().to_string();
        assert!(err.contains("trailing"), "got: {err}");
        assert!(err.contains("line 1"), "got: {err}");
    }

    // ── property-based round-trip ────────────────────────

    /// Signal names, including namespaced ones (`Core.instruction_fetch`).
    fn name_strategy() -> impl Strategy<Value = String> {
        "[a-zA-Z_][a-zA-Z0-9_.]*"
    }

    fn delay() -> impl Strategy<Value = u32> {
        0..10u32
    }

    /// Leaves: signals, `NAME[N]` repetitions, and integer literals (the
    /// grammar only allows `NAME[N]`, so `Repeat` never wraps a composite
    /// expression).
    fn leaf() -> impl Strategy<Value = Expr> {
        prop_oneof![
            name_strategy().prop_map(Expr::Signal),
            (name_strategy(), delay())
                .prop_map(|(n, c)| Expr::Repeat(Box::new(Expr::Signal(n)), c)),
            (0..10000u64).prop_map(Expr::Const),
        ]
    }

    fn cmp_op() -> impl Strategy<Value = CmpOp> {
        prop_oneof![
            Just(CmpOp::Eq),
            Just(CmpOp::Ne),
            Just(CmpOp::Lt),
            Just(CmpOp::Le),
            Just(CmpOp::Gt),
            Just(CmpOp::Ge),
        ]
    }

    fn sequence_arb(inner: impl Strategy<Value = Expr> + Clone) -> impl Strategy<Value = Expr> {
        (inner.clone(), vec((delay(), inner), 1..4)).prop_map(|(first, rest)| {
            let mut steps = vec![SequenceStep {
                expr: Box::new(first),
                delay: 0,
            }];
            for (d, e) in rest {
                steps.push(SequenceStep {
                    expr: Box::new(e),
                    delay: d,
                });
            }
            Expr::Sequence(steps)
        })
    }

    fn arb(depth: u32) -> impl Strategy<Value = Expr> {
        leaf().prop_recursive(depth, 64, 16, |inner| {
            prop_oneof![
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::And(Box::new(a), Box::new(b))),
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::Or(Box::new(a), Box::new(b))),
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::FirstAfter(Box::new(a), Box::new(b))),
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::Overlapping(Box::new(a), Box::new(b))),
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::Interval(Box::new(a), Box::new(b))),
                (inner.clone(), inner.clone())
                    .prop_map(|(a, b)| Expr::Implication(Box::new(a), Box::new(b))),
                (inner.clone(), delay(), inner.clone()).prop_map(|(a, n, b)| Expr::FixedDelay(
                    Box::new(a),
                    n,
                    Box::new(b)
                )),
                (inner.clone(), delay(), inner.clone()).prop_map(|(a, n, b)| Expr::Within(
                    Box::new(a),
                    n,
                    Box::new(b)
                )),
                (inner.clone(), cmp_op(), inner.clone())
                    .prop_map(|(a, op, b)| Expr::Cmp(Box::new(a), op, Box::new(b))),
                inner.clone().prop_map(|a| Expr::Not(Box::new(a))),
                sequence_arb(inner.clone()),
                // Function calls: bare-name only (dotted names are
                // namespace references, not callable).
                ("[a-zA-Z_][a-zA-Z0-9_]*", vec(inner.clone(), 0..3))
                    .prop_map(|(n, args)| Expr::Call(n, args)),
            ]
        })
    }

    proptest! {
        /// `Display` output must re-parse to an identical AST.
        #[test]
        fn display_roundtrip(expr in arb(4)) {
            let s = expr.to_string();
            let parsed = parse(&s).unwrap_or_else(|e| panic!("failed to reparse '{s}': {e}"));
            prop_assert_eq!(parsed, expr);
        }
    }
}
