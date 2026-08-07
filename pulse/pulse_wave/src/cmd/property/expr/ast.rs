/// AST for pulse event expressions.
///
/// Grammar (entry point is `event`):
/// ```ebnf
/// event    = implication
/// implication = sequence ("|->" sequence)?
/// sequence = delay (">>" delay)*
/// delay    = term ("->" term)? | term ("~>" term)? | term ("~~" term)?
///          | term ("->" INT term)? | term ("--" INT term)?
///          | term
/// term     = comparison (("&&" | "||") comparison)*
/// comparison = factor (("==" | "!=" | ">=" | "<=" | ">" | "<") factor)?
/// factor   = "!"* atom
/// atom     = NAME | NAME "[" INT "]" | INT | "0x" HEX | "(" event ")"
///          | NAME "(" [event ("," event)*] ")"   -- function call
/// ```
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Expr {
    /// `A && B`
    And(Box<Expr>, Box<Expr>),
    /// `A || B`
    Or(Box<Expr>, Box<Expr>),
    /// `!A`
    Not(Box<Expr>),
    /// A signal / event name
    Signal(String),
    /// `A[N]` — true for N consecutive cycles
    Repeat(Box<Expr>, u32),

    /// `A -> B` — first B after A
    FirstAfter(Box<Expr>, Box<Expr>),
    /// `A ->N B` — exactly Nth cycle after A
    FixedDelay(Box<Expr>, u32, Box<Expr>),
    /// `A --N B` — B within N cycles after A
    Within(Box<Expr>, u32, Box<Expr>),
    /// `A ~> B` — each B after A (overlapping)
    Overlapping(Box<Expr>, Box<Expr>),
    /// `A ~~ B` — interval from A to B
    Interval(Box<Expr>, Box<Expr>),

    /// `A == B` / `A != B` / `A < B` / ... — value comparison (multi-bit operands)
    Cmp(Box<Expr>, CmpOp, Box<Expr>),
    /// Integer literal (decimal or `0x` hex)
    Const(u64),
    /// Standard-library function call: `name(arg1, arg2, ...)`
    Call(String, Vec<Expr>),

    /// `A |-> B` — A implies B same cycle
    Implication(Box<Expr>, Box<Expr>),
    /// `A >>N B` — pipeline sequence
    Sequence(Vec<SequenceStep>),
}

#[derive(Debug, Clone, PartialEq)]
pub(crate) struct SequenceStep {
    pub expr: Box<Expr>,
    pub delay: u32,
}

/// Value comparison operators for `Expr::Cmp`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CmpOp {
    Eq,
    Ne,
    Lt,
    Le,
    Gt,
    Ge,
}

impl std::fmt::Display for CmpOp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            CmpOp::Eq => "==",
            CmpOp::Ne => "!=",
            CmpOp::Lt => "<",
            CmpOp::Le => "<=",
            CmpOp::Gt => ">",
            CmpOp::Ge => ">=",
        };
        write!(f, "{s}")
    }
}

/// Canonical rendering of an expression.
///
/// Every binary/temporal operator is parenthesized so that `Display` output
/// always re-parses to an identical AST (`parse(expr.to_string()) == expr`),
/// which the proptest round-trip suite relies on. `Repeat`'s inner expression
/// is printed without parentheses because the grammar only accepts `NAME[N]`.
impl std::fmt::Display for Expr {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Expr::And(a, b) => write!(f, "({a} && {b})"),
            Expr::Or(a, b) => write!(f, "({a} || {b})"),
            Expr::Not(a) => write!(f, "!{a}"),
            Expr::Signal(name) => write!(f, "{name}"),
            Expr::Repeat(a, n) => write!(f, "{a}[{n}]"),
            Expr::FirstAfter(a, b) => write!(f, "({a} -> {b})"),
            Expr::FixedDelay(a, n, b) => write!(f, "({a} ->{n} {b})"),
            Expr::Within(a, n, b) => write!(f, "({a} --{n} {b})"),
            Expr::Overlapping(a, b) => write!(f, "({a} ~> {b})"),
            Expr::Interval(a, b) => write!(f, "({a} ~~ {b})"),
            Expr::Implication(a, b) => write!(f, "({a} |-> {b})"),
            Expr::Cmp(a, op, b) => write!(f, "({a} {op} {b})"),
            Expr::Const(v) => write!(f, "0x{v:x}"),
            Expr::Call(name, args) => {
                write!(f, "{name}(")?;
                for (i, arg) in args.iter().enumerate() {
                    if i > 0 {
                        write!(f, ", ")?;
                    }
                    write!(f, "{arg}")?;
                }
                write!(f, ")")
            }
            Expr::Sequence(steps) => {
                let (first, rest) = steps.split_first().expect("sequence is never empty");
                write!(f, "({}", first.expr)?;
                for step in rest {
                    write!(f, " >>{} {}", step.delay, step.expr)?;
                }
                write!(f, ")")
            }
        }
    }
}
