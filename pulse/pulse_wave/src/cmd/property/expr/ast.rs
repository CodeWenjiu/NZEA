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
/// term     = factor (("&&" | "||") factor)*
/// factor   = "!"? atom
/// atom     = NAME | NAME "[" INT "]" | "(" event ")"
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
