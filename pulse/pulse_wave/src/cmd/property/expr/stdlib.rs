//! Standard library for event expressions, loaded from `std.pulse`.
//!
//! Templates are expanded by **text substitution** before parsing: calling
//! `prev(x, 2)` renders the arguments, splices them into the template body,
//! and parses the result. This lets parameters appear in any syntactic
//! position — including temporal delays (`x ->2 1`). The whole stdlib is
//! written in the temporal language itself; the evaluator knows no
//! built-in functions (`prev(x, n)` is `x ->n 1`, not a primitive).

use std::collections::BTreeMap;
use std::sync::OnceLock;

use super::ast::Expr;

/// Template parameters are named `__p1`, `__p2`, ... — outside the signal
/// name character set, so they can never collide with real signals.
const PARAM_PREFIX: &str = "__p";

/// A template: parameter names plus the raw body text (not yet parsed,
/// since parameters may sit in positions the parser rejects, e.g. `->__p2`).
struct Template {
    params: Vec<String>,
    body: String,
}

/// Loaded templates: function name → template.
fn stdlib() -> &'static BTreeMap<String, Template> {
    static STDLIB: OnceLock<BTreeMap<String, Template>> = OnceLock::new();
    STDLIB.get_or_init(|| {
        let content = include_str!("std.pulse");
        let mut lib = BTreeMap::new();
        for (i, line) in content.lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("--") {
                continue;
            }
            let (name, params, body) = parse_template(line)
                .unwrap_or_else(|e| panic!("std.pulse:{}: {e}", i + 1));
            validate_template(&params, &body)
                .unwrap_or_else(|e| panic!("std.pulse:{}: {e}", i + 1));
            lib.insert(name, Template { params, body });
        }
        lib
    })
}

/// Parse a template line `name(__p1, __p2) = <expr>`.
fn parse_template(line: &str) -> Result<(String, Vec<String>, String), String> {
    let (head, body) = line
        .split_once('=')
        .ok_or_else(|| format!("expected 'name(params) = expr', got '{line}'"))?;
    let head = head.trim();
    let (name, params_str) = head
        .split_once('(')
        .ok_or_else(|| format!("expected '(' after function name in '{line}'"))?;
    let params_str = params_str
        .strip_suffix(')')
        .ok_or_else(|| format!("expected ')' in '{line}'"))?;
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err(format!("missing function name in '{line}'"));
    }
    let params: Vec<String> = params_str
        .split(',')
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_empty())
        .collect();
    for p in &params {
        if !p.starts_with(PARAM_PREFIX) {
            return Err(format!(
                "parameter '{p}' must be named {PARAM_PREFIX}N (reserved namespace)"
            ));
        }
    }
    Ok((name, params, body.trim().to_string()))
}

/// Check the template body parses once parameters are replaced with
/// syntactically valid stand-ins (`0` works for operand and delay
/// positions alike), and that it contains no function calls — templates
/// must be pure core syntax, otherwise a nested call would survive
/// desugaring and panic in the evaluator. Catches template typos at load
/// time instead of at the first call.
fn validate_template(params: &[String], body: &str) -> Result<(), String> {
    let mut text = body.to_string();
    // Replace longest parameters first so `__p1` never hits inside `__p12`.
    let mut order: Vec<usize> = (0..params.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(params[i].len()));
    for i in order {
        text = text.replace(&params[i], "0");
    }
    let ast = super::parser::parse(&text).map_err(|e| e.to_string())?;
    super::walk::visit(&ast, &mut |e| {
        if let Expr::Call(name, _) = e {
            return Err(format!("template body may not call function '{name}'"));
        }
        Ok(())
    })
}

/// Validate every function call in an expression: name, arity, and argument
/// shapes. Runs after normalization, before desugaring.
pub(crate) fn check_functions(expr: &Expr) -> Result<(), String> {
    super::walk::visit(expr, &mut |e| {
        if let Expr::Call(name, args) = e {
            check_call(name, args)?;
        }
        Ok(())
    })
}

/// Validate a single call against the loaded templates.
fn check_call(name: &str, args: &[Expr]) -> Result<(), String> {
    // `prev` splices its second argument into a delay position (`->n`),
    // which only accepts a decimal constant — a semantic constraint no
    // template can express, so it lives here next to the template.
    if name == "prev" {
        if args.len() != 2 {
            return Err(format!(
                "prev: expected 2 arguments (signal, depth), got {}",
                args.len()
            ));
        }
        return match &args[1] {
            Expr::Const(n) if *n >= 1 => Ok(()),
            _ => Err("prev: depth must be a constant >= 1".into()),
        };
    }
    match stdlib().get(name) {
        Some(tmpl) if args.len() == tmpl.params.len() => Ok(()),
        Some(tmpl) => Err(format!(
            "{name}: expected {} {}, got {}",
            tmpl.params.len(),
            if tmpl.params.len() == 1 {
                "argument"
            } else {
                "arguments"
            },
            args.len()
        )),
        None => Err(format!("unknown function '{name}'")),
    }
}

/// Replace stdlib calls with their expanded template bodies. Assumes
/// `check_functions` has already validated the tree; after this pass no
/// `Call` nodes remain, so the evaluator never sees a function.
pub(crate) fn desugar(expr: &Expr) -> Expr {
    super::walk::map(expr, &mut |e| match e {
        Expr::Call(name, args) => {
            let tmpl = stdlib()
                .get(name)
                .unwrap_or_else(|| panic!("validated by check_functions: '{name}'"));
            expand(tmpl, args)
                .unwrap_or_else(|e| panic!("std.pulse template '{name}': {e}"))
        }
        _ => e.clone(),
    })
}

/// Splice the rendered arguments into the template body and parse it.
fn expand(tmpl: &Template, args: &[Expr]) -> Result<Expr, String> {
    // Replace longest parameters first so `__p1` never hits inside `__p12`.
    let mut order: Vec<usize> = (0..tmpl.params.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(tmpl.params[i].len()));
    let mut text = tmpl.body.clone();
    for i in order {
        text = text.replace(&tmpl.params[i], &render_arg(&args[i]));
    }
    super::parser::parse(&text).map_err(|e| e.to_string())
}

/// Render an argument for splicing: constants in decimal (delay positions
/// only accept decimal integers), everything else via `Display`, which is
/// round-trip safe.
fn render_arg(e: &Expr) -> String {
    match e {
        Expr::Const(v) => v.to_string(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(s: &str) -> Expr {
        super::super::parser::parse(s).unwrap()
    }

    #[test]
    fn stdlib_loads_templates() {
        let lib = stdlib();
        assert_eq!(lib.len(), 4, "std.pulse must define rise/fall/stable/prev");
        for (name, arity) in [("rise", 1), ("fall", 1), ("stable", 1), ("prev", 2)] {
            let tmpl = &lib[name];
            assert_eq!(tmpl.params.len(), arity, "{name}");
        }
    }

    #[test]
    fn check_accepts_valid_calls() {
        assert!(check_functions(&p("prev(a, 2)")).is_ok());
        assert!(check_functions(&p("rise(a) && fall(b)")).is_ok());
        assert!(check_functions(&p("stable(a || b)")).is_ok());
    }

    #[test]
    fn check_rejects_unknown_and_bad_arity() {
        assert_eq!(
            check_functions(&p("foo(a)")).unwrap_err(),
            "unknown function 'foo'"
        );
        assert_eq!(
            check_functions(&p("prev(a)")).unwrap_err(),
            "prev: expected 2 arguments (signal, depth), got 1"
        );
        assert_eq!(
            check_functions(&p("rise()")).unwrap_err(),
            "rise: expected 1 argument, got 0"
        );
        assert_eq!(
            check_functions(&p("prev(a, b)")).unwrap_err(),
            "prev: depth must be a constant >= 1"
        );
        assert_eq!(
            check_functions(&p("prev(a, 0)")).unwrap_err(),
            "prev: depth must be a constant >= 1"
        );
        // Nested calls are validated too.
        assert!(check_functions(&p("rise(prev(a, 2))")).is_ok());
        assert_eq!(
            check_functions(&p("rise(foo(a))")).unwrap_err(),
            "unknown function 'foo'"
        );
    }

    #[test]
    fn desugar_rise() {
        let out = desugar(&p("rise(a)"));
        assert_eq!(out.to_string(), "(!a ->1 a)");
        assert!(check_functions(&out).is_ok());
    }

    #[test]
    fn desugar_fall() {
        let out = desugar(&p("fall(a)"));
        assert_eq!(out.to_string(), "(a ->1 !a)");
        assert!(check_functions(&out).is_ok());
    }

    #[test]
    fn desugar_stable() {
        let out = desugar(&p("stable(a)"));
        assert_eq!(out.to_string(), "((a ->1 a) || (!a ->1 !a))");
        assert!(check_functions(&out).is_ok());
    }

    #[test]
    fn desugar_prev() {
        let out = desugar(&p("prev(a, 2)"));
        assert_eq!(out.to_string(), "(a ->2 0x1)");
        assert!(check_functions(&out).is_ok());
        // Hex depth renders as decimal so the delay position parses.
        let out = desugar(&p("prev(a, 0x10)"));
        assert_eq!(out.to_string(), "(a ->16 0x1)");
    }

    #[test]
    fn desugar_substitutes_composite_arguments() {
        let out = desugar(&p("rise(a && b)"));
        assert_eq!(out.to_string(), "(!(a && b) ->1 (a && b))");
        // Nested stdlib calls expand bottom-up.
        let out = desugar(&p("rise(prev(a, 2))"));
        assert_eq!(out.to_string(), "(!(a ->2 0x1) ->1 (a ->2 0x1))");
    }

    #[test]
    fn desugar_is_idempotent_on_core_expressions() {
        let ast = p("(a && !b) ->3 (c || d)");
        assert_eq!(desugar(&ast), ast);
    }
}
