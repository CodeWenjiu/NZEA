use std::path::PathBuf;

use clap::builder::styling::{AnsiColor, Styles};

use crate::SerdeRange;

#[derive(clap::Parser, Debug)]
#[command(
    author,
    version,
    about,
    styles = Styles::styled()
        .header(AnsiColor::Green.on_default().bold())
        .usage(AnsiColor::Green.on_default().bold())
        .literal(AnsiColor::Blue.on_default().bold())
        .placeholder(AnsiColor::Cyan.on_default())
)]
pub struct PulseCommand {
    /// Path to VCD/FST waveform file (default: $NZEA_TRACE_FST)
    #[arg(long, value_name = "FILE", env = "NZEA_TRACE_FST")]
    pub wave: PathBuf,

    /// Output machine-readable JSON instead of human-readable text
    #[arg(long, global = true)]
    pub json: bool,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, clap::Subcommand)]
pub enum Command {
    /// Show waveform metadata (time unit, bounds, format, top-level scopes)
    Info,

    /// List scopes matching a filter (recursive hierarchy traversal)
    Scope(ScopeArgs),

    /// List signals within a scope
    Signal(SignalArgs),

    /// Sample signal values at specific timestamps
    Value(ValueArgs),

    /// Find timestamps where an event expression is true
    Property(PropertyArgs),
}

#[derive(Debug, clap::Args)]
pub struct ScopeArgs {
    /// Root scope to expand from (default: top-level scope)
    #[arg(long, value_name = "PATH")]
    pub root: Option<String>,

    /// Maximum expansion depth from root (default: 2)
    #[arg(long, value_name = "N", default_value = "2")]
    pub depth: u32,

    /// Substring filter for scope names
    #[arg(long, value_name = "TERM")]
    pub filter: Option<String>,

    /// Output flat full-path list (for piping)
    #[arg(long)]
    pub flat: bool,
}

#[derive(Debug, clap::Args)]
pub struct SignalArgs {
    /// Target scope path (e.g. "TOP.NzeaTile.icache")
    #[arg(long, value_name = "SCOPE")]
    pub scope: String,
}

#[derive(Debug, clap::Args)]
pub struct ValueArgs {
    /// Target scope path
    #[arg(long, value_name = "SCOPE")]
    pub scope: String,

    /// Sample ticks: "100,200,100-200,-100,100-"
    #[arg(
        long,
        value_name = "TICKS",
        value_delimiter = ',',
        allow_hyphen_values = true
    )]
    pub at: Vec<SerdeRange<u64>>,

    /// Comma-separated signal names to sample
    #[arg(long, value_name = "NAMES", value_delimiter = ',')]
    pub signals: Vec<String>,
}

#[derive(Debug, clap::Args)]
pub struct PropertyArgs {
    /// Target scope path (default: top-level scope)
    #[arg(long, value_name = "SCOPE")]
    pub scope: Option<String>,

    /// Clock edge expression (default: "posedge clock")
    #[arg(long, value_name = "EDGE", default_value = "posedge clock")]
    pub on: String,

    /// Event expression to evaluate
    #[arg(long, value_name = "EXPR")]
    pub eval: String,

    /// Event definitions: "SOURCE SCOPE NAME" (repeatable). SOURCE is a .pulse file
    /// or an inline "name = expr" string.
    #[arg(long, value_name = "SOURCE SCOPE NAME")]
    pub event: Vec<EventDef>,

    /// Cycle range, e.g. "0-100", "500-", "-100" (default: full trace)
    #[arg(long, value_name = "FROM-TO", allow_hyphen_values = true)]
    pub cycles: Option<SerdeRange<u64>>,

    /// Return at most the first N matches (truncated after evaluation)
    #[arg(long, value_name = "N")]
    pub max: Option<usize>,
}

#[derive(Debug, Clone)]
pub struct EventDef {
    pub source: String,
    pub scope: String,
    pub name: String,
}

impl std::str::FromStr for EventDef {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        use winnow::ascii::multispace1;
        use winnow::error::ContextError;
        use winnow::prelude::*;
        use winnow::token::take_till;

        fn field<'a>(input: &mut &'a str) -> winnow::Result<&'a str, ContextError> {
            take_till(1.., |c: char| c.is_whitespace()).parse_next(input)
        }

        fn sep<'a>(input: &mut &'a str) -> winnow::Result<&'a str, ContextError> {
            multispace1.parse_next(input)
        }

        let mut input = s;
        let source = field(&mut input).map_err(|_| "missing SOURCE in --event".to_string())?;
        sep(&mut input).map_err(|_| "missing SCOPE in --event".to_string())?;
        let scope = field(&mut input).map_err(|_| "missing SCOPE in --event".to_string())?;
        sep(&mut input).map_err(|_| "missing NAME in --event".to_string())?;
        let name = field(&mut input).map_err(|_| "missing NAME in --event".to_string())?;
        if !input.trim().is_empty() {
            return Err(format!("expected 'SOURCE SCOPE NAME', got '{s}'"));
        }
        Ok(EventDef {
            source: source.to_string(),
            scope: scope.to_string(),
            name: name.to_string(),
        })
    }
}
