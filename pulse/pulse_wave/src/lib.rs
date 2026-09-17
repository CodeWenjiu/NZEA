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

    pub fn run(mut self, cmd: Command) -> Result<(), WaveError> {
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
            Command::Skill => {
                print!("{}", SKILL_MARKDOWN);
                Ok(())
            }
        }
    }
}

/// Entry point for `pulse diff`: unlike every other command it needs TWO
/// waveforms and therefore bypasses `Pulse::new` (which binds `--wave`).
/// Dispatched from `pulse_cli/main.rs` before the single-wave path.
pub fn diff(json: bool, args: &crate::command::DiffArgs) -> Result<(), WaveError> {
    cmd::run_diff(json, args)
}
