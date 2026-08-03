use wellen::simple;

pulse_macro::mod_flat!(command, error, time_spec, tree);
pulse_macro::mod_pub!(cmd);

pub struct Pulse {
    wav: simple::Waveform,
    json: bool,
}

impl Pulse {
    pub fn new(cli: &PulseCommand) -> Result<Self, WaveError> {
        let wav = simple::read(&cli.wave)?;
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
            Command::Value(args) => self.value(&args.scope, &args.at, &args.signals),
            Command::Property(args) => self.property(
                args.scope.as_deref(),
                &args.on,
                &args.eval,
                &args.event,
                args.cycles,
                args.max,
            ),
        }
    }
}
