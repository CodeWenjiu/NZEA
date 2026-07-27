use std::path::PathBuf;

use clap::builder::styling::{AnsiColor, Styles};

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
    /// Target scope path (e.g. "TOP.NzeaTile.icache"), or "-" to read from stdin
    #[arg(long, value_name = "SCOPE")]
    pub scope: String,

    /// Substring filter for signal names
    #[arg(long, value_name = "TERM")]
    pub filter: Option<String>,
}

#[derive(Debug, clap::Args)]
pub struct ValueArgs {
    /// Target scope path, or "-" to read from stdin
    #[arg(long, value_name = "SCOPE")]
    pub scope: String,

    /// Comma-separated timestamps (e.g. "100ns,200ns,1us")
    #[arg(long, value_name = "TIMES", value_delimiter = ',')]
    pub at: Vec<String>,

    /// Comma-separated signal names to sample
    #[arg(long, value_name = "NAMES", value_delimiter = ',')]
    pub signals: Vec<String>,
}

#[derive(Debug, clap::Args)]
pub struct PropertyArgs {
    /// Target scope path, or "-" to read from stdin
    #[arg(long, value_name = "SCOPE")]
    pub scope: String,

    /// Clock edge expression (default: "posedge clock")
    #[arg(long, value_name = "EDGE", default_value = "posedge clock")]
    pub on: String,

    /// Event expression to evaluate
    #[arg(long, value_name = "EXPR")]
    pub eval: String,

    /// Cycle range, e.g. "0-100", "500-" (default: full trace)
    #[arg(long, value_name = "FROM-TO")]
    pub cycles: Option<String>,
}
