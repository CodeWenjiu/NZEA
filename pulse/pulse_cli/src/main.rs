use clap::Parser;

fn main() -> anyhow::Result<()> {
    let cli = pulse_wave::PulseCommand::parse();
    // `skill` is self-contained: it must work without a waveform file.
    if let pulse_wave::Command::Skill = &cli.command {
        print!("{}", pulse_wave::SKILL_MARKDOWN);
        return Ok(());
    }
    pulse_wave::Pulse::new(&cli)?.run(cli.command)?;
    Ok(())
}
