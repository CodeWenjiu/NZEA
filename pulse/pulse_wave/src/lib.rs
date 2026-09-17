use clap::{CommandFactory, FromArgMatches};
use wellen::simple;

pulse_macro::mod_flat!(command, cycle, error, radix, read, skill, time_spec, tree);
pulse_macro::mod_pub!(cmd);

pub struct Pulse {
    wav: simple::Waveform,
    json: bool,
}

impl Pulse {
    pub fn new(cli: &PulseCommand) -> Result<Self, WaveError> {
        let wave = cli.wave.clone().ok_or(WaveError::NoWaveform)?;
        let wav = simple::read(&wave)?;
        Ok(Self {
            wav,
            json: cli.json,
        })
    }

    fn emit<T: serde::Serialize + std::fmt::Display>(&self, out: &T) {
        if self.json {
            println!("{}", serde_json::to_string(out).unwrap_or_default());
        } else {
            println!("{out}");
        }
    }

    pub fn run(&mut self, cmd: Command) -> Result<(), WaveError> {
        match cmd {
            Command::Info => self.info(),
            Command::Scope(args) => self.scope(
                args.depth,
                args.filter.as_deref(),
                args.flat,
                args.root.as_deref(),
            ),
            Command::Signal(args) => self.signal(&args.scope),
            Command::Value(args) => self.value(
                &args.scope,
                &args.at,
                args.changes,
                &args.signals,
                &args.radix,
            ),
            Command::Plot(args) => self.plot(
                &args.scope,
                &args.signals,
                args.cycles,
                &args.mark,
                args.out.as_deref(),
            ),
            Command::Diff(args) => cmd::run_diff(self.json, &args),
            Command::Property(args) => self.property(
                args.scope.as_deref(),
                args.on.as_deref(),
                &args.eval,
                &args.event,
                args.cycles,
                args.max,
                args.count,
                &args.sample,
                &args.radix,
            ),
            Command::Batch(args) => {
                let script = read_script(&args.script)?;
                if !self.batch(&script, args.keep_going)? {
                    return Err(WaveError::BatchFailed);
                }
                Ok(())
            }
            Command::Skill => {
                print!("{}", SKILL_MARKDOWN);
                Ok(())
            }
        }
    }

    /// Execute one command per non-empty, non-comment script line, all on
    /// this instance: the waveform is loaded once and every signal stays in
    /// the cache, so repeated queries cost a fraction of the first.
    ///
    /// Command syntax is identical to the CLI (quotes group words); `--wave`
    /// is session-level and must not appear in lines. Inline `--json` is
    /// accepted and overrides the output format for that line only. Command
    /// echo goes to stderr so stdout stays pure output (NDJSON under
    /// `--json`). Returns `false` when any command failed; without
    /// `--keep-going` execution stops at the first failure.
    pub fn batch(&mut self, script: &str, keep_going: bool) -> Result<bool, WaveError> {
        let mut ok = true;
        for (lineno, raw) in script.lines().enumerate() {
            let line = raw.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            eprintln!("> {line}");
            let tokens = match split_command(line) {
                Ok(t) => t,
                Err(e) => {
                    eprintln!("line {}: {e}", lineno + 1);
                    ok = false;
                    if !keep_going {
                        break;
                    }
                    continue;
                }
            };
            // Parse with the top-level command (lines carry a full subcommand
            // invocation); --wave/--json are session-level and rejected below.
            let matches = match PulseCommand::command()
                .no_binary_name(true)
                .try_get_matches_from(&tokens)
            {
                Ok(m) => m,
                Err(e) => {
                    eprintln!("line {}", lineno + 1);
                    eprintln!("{e}");
                    ok = false;
                    if !keep_going {
                        break;
                    }
                    continue;
                }
            };
            let cli = match PulseCommand::from_arg_matches(&matches) {
                Ok(c) => c,
                Err(e) => {
                    eprintln!("line {}: {e}", lineno + 1);
                    ok = false;
                    if !keep_going {
                        break;
                    }
                    continue;
                }
            };
            // Reject --wave only when the line spells it out (the waveform is
            // session-fixed; the session value may legitimately arrive via
            // the NZEA_TRACE_FST env var). Inline --json is allowed and
            // temporarily overrides the session output format for that line.
            let wave_inline = matches!(
                matches.value_source("wave"),
                Some(clap::parser::ValueSource::CommandLine)
            );
            if wave_inline {
                eprintln!(
                    "line {}: --wave is session-level (pass it to `pulse batch`, not per line)",
                    lineno + 1
                );
                ok = false;
                if !keep_going {
                    break;
                }
                continue;
            }
            let line_json = matches!(
                matches.value_source("json"),
                Some(clap::parser::ValueSource::CommandLine)
            );
            let cmd = cli.command;
            let session_json = self.json;
            if line_json {
                self.json = true;
            }
            let r = self.run(cmd);
            if line_json {
                self.json = session_json;
            }
            if let Err(e) = r {
                eprintln!("line {}: {e}", lineno + 1);
                ok = false;
                if !keep_going {
                    break;
                }
            }
        }
        Ok(ok)
    }
}

/// Entry point for `pulse diff`: unlike every other command it needs TWO
/// waveforms and therefore bypasses `Pulse::new` (which binds `--wave`).
/// Dispatched from `pulse_cli/main.rs` before the single-wave path.
pub fn diff(json: bool, args: &crate::command::DiffArgs) -> Result<(), WaveError> {
    cmd::run_diff(json, args)
}

/// Read the batch script source: a file path, or stdin when the argument is
/// `-` (the default).
fn read_script(source: &str) -> Result<String, WaveError> {
    if source == "-" {
        let mut s = String::new();
        std::io::Read::read_to_string(&mut std::io::stdin(), &mut s)?;
        Ok(s)
    } else {
        std::fs::read_to_string(source).map_err(WaveError::Io)
    }
}

/// Split one script line into command tokens: whitespace separates words,
/// single/double quotes group words (quote chars are consumed, contents
/// kept verbatim; no escape processing — same quoting the CLI accepts).
fn split_command(line: &str) -> Result<Vec<String>, String> {
    let mut out: Vec<String> = Vec::new();
    let mut cur = String::new();
    let mut in_word = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\'' || c == '\"' {
            in_word = true;
            let mut closed = false;
            for c2 in chars.by_ref() {
                if c2 == c {
                    closed = true;
                    break;
                }
                cur.push(c2);
            }
            if !closed {
                return Err("unterminated quote".into());
            }
        } else if c.is_whitespace() {
            if in_word {
                out.push(std::mem::take(&mut cur));
                in_word = false;
            }
        } else {
            cur.push(c);
            in_word = true;
        }
    }
    if in_word {
        out.push(cur);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::split_command;

    #[test]
    fn splits_plain_words() {
        assert_eq!(
            split_command("property --count").unwrap(),
            ["property", "--count"]
        );
    }

    #[test]
    fn quotes_group_words_verbatim() {
        assert_eq!(
            split_command("--at 'match(a && b)' --sample \"pc == 0x10\"").unwrap(),
            ["--at", "match(a && b)", "--sample", "pc == 0x10"]
        );
    }

    #[test]
    fn quotes_can_be_adjacent_to_words() {
        assert_eq!(split_command("a'b'c").unwrap(), ["abc"]);
    }

    #[test]
    fn empty_line_yields_no_tokens() {
        assert_eq!(split_command("  ").unwrap(), Vec::<String>::new());
    }

    #[test]
    fn unterminated_quote_errors() {
        assert!(split_command("--at 'oops").is_err());
    }

    #[test]
    fn multiple_quoted_ranges() {
        assert_eq!(
            split_command("--at '100,200' --at '300'").unwrap(),
            ["--at", "100,200", "--at", "300"]
        );
    }
}
