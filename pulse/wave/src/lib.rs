use clap::{CommandFactory, FromArgMatches, Parser};
use wellen::simple;

nzea_macro::mod_flat!(command, cycle, error, radix, read, skill, time_spec, tree);
nzea_macro::mod_pub!(cmd);

/// Parse process arguments, exiting with clap's message plus the quoting tip on
/// error: the binary uses this instead of `Parser::parse` so that a mistyped
/// batch line points at the quoting rule rather than only at the stray token
/// clap found.
pub fn parse_args() -> PulseCommand {
    match PulseCommand::try_parse() {
        Ok(cli) => cli,
        Err(e) => hint_quoting(e).exit(),
    }
}

/// Load a waveform, turning the reader's failures — including panics — into
/// `WaveError`s.
///
/// wellen's FST reader `assert!`s on malformed blocks (typically the
/// half-written tail of a dump whose writer was killed) instead of returning an
/// error; the release profile therefore must keep `panic = "unwind"` so that a
/// bad dump cannot abort the whole process. The hook is silenced while parsing
/// but the payload text is kept in the error.
pub fn load_waveform(path: &std::path::Path) -> Result<simple::Waveform, WaveError> {
    if !path.is_file() {
        return Err(WaveError::NotAFile {
            path: path.display().to_string(),
        });
    }
    let owned = path.to_path_buf();
    match catch_unwind_quietly(move || simple::read(&owned)) {
        Ok(Ok(wav)) => Ok(wav),
        Ok(Err(e)) => Err(WaveError::unreadable(path, e)),
        Err(panic) => Err(WaveError::unreadable(
            path,
            format!("the waveform reader panicked: {panic}"),
        )),
    }
}

fn catch_unwind_quietly<T>(f: impl FnOnce() -> T + std::panic::UnwindSafe) -> Result<T, String> {
    let hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {}));
    let r = std::panic::catch_unwind(f);
    std::panic::set_hook(hook);
    r.map_err(|payload| panic_text(payload.as_ref()))
}

fn panic_text(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

/// Add a quoting tip to clap's "unexpected argument" errors: expressions and
/// signal lists are single arguments, so an unquoted `&&`, `||` or `>` arrives
/// as a stray token and clap can only report that the argument is unexpected.
fn hint_quoting(err: clap::Error) -> clap::Error {
    use clap::error::{ContextKind, ContextValue, ErrorKind};
    if !matches!(
        err.kind(),
        ErrorKind::UnknownArgument | ErrorKind::TooManyValues
    ) {
        return err;
    }
    let mut err = err;
    err.insert(
        ContextKind::Suggested,
        ContextValue::StyledStrs(vec![
            "expressions are single arguments: quote anything containing spaces or shell \
             operators, e.g. --eval 'fire && !stall'"
                .into(),
        ]),
    );
    err
}

pub struct Pulse {
    wav: simple::Waveform,
    json: bool,
}

impl Pulse {
    pub fn new(cli: &PulseCommand) -> Result<Self, WaveError> {
        let wave = cli.wave.clone().ok_or(WaveError::NoWaveform)?;
        Ok(Self {
            wav: load_waveform(&wave)?,
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

    /// Execute one command per script line, all on this instance: the waveform
    /// is loaded once and every signal stays in the cache, so repeated queries
    /// cost a fraction of the first.
    ///
    /// Command syntax is identical to the CLI (quotes group words); `--wave`
    /// is session-level and must not appear in lines. Inline `--json` is
    /// accepted and overrides the output format for that line only. Command
    /// echo goes to stderr so stdout stays pure output (NDJSON under
    /// `--json`).
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
            // Lines carry a full subcommand invocation, so parse with the
            // top-level command.
            let matches = match PulseCommand::command()
                .no_binary_name(true)
                .try_get_matches_from(&tokens)
            {
                Ok(m) => m,
                Err(e) => {
                    eprintln!("line {}", lineno + 1);
                    eprintln!("{}", hint_quoting(e));
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
                    eprintln!("line {}: {}", lineno + 1, hint_quoting(e));
                    ok = false;
                    if !keep_going {
                        break;
                    }
                    continue;
                }
            };
            // Reject --wave only when the line spells it out: the session
            // value may legitimately arrive via the NZEA_TRACE_FST env var.
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

/// Entry point for `pulse diff`: it needs TWO waveforms and therefore bypasses
/// `Pulse::new` (which binds a single `--wave`).
pub fn diff(json: bool, args: &crate::command::DiffArgs) -> Result<(), WaveError> {
    cmd::run_diff(json, args)
}

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
/// single/double quotes group words (quote chars consumed, contents kept
/// verbatim; no escape processing).
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
    use super::{catch_unwind_quietly, load_waveform, split_command};

    #[test]
    fn reader_panics_become_errors() {
        let msg = catch_unwind_quietly(|| panic!("assertion failed: left == right"))
            .expect_err("a panic must not escape the waveform loader");
        assert!(msg.contains("assertion failed"), "unexpected text: {msg}");
    }

    #[test]
    fn an_unreadable_dump_reports_the_truncation_hint() {
        let path = std::env::temp_dir().join("pulse-test-unreadable.fst");
        std::fs::write(&path, [0u8; 9]).unwrap();
        let msg = load_waveform(&path).unwrap_err().to_string();
        let _ = std::fs::remove_file(&path);
        assert!(msg.contains("truncated"), "unexpected message: {msg}");
    }

    #[test]
    fn a_missing_file_is_reported_as_such() {
        let path = std::path::Path::new("/nonexistent/pulse-test/wave.fst");
        let msg = load_waveform(path).unwrap_err().to_string();
        assert!(
            msg.contains("not a readable waveform file"),
            "unexpected message: {msg}"
        );
    }

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
