use clap::Parser;

fn main() -> anyhow::Result<()> {
    let cli = pulse_wave::PulseCommand::parse();
    pulse_wave::Pulse::new(&cli)?.run(cli.command)?;
    Ok(())
}
