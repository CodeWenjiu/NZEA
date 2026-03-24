//! Wave tracker binary: thin entrypoint over [`wave_tracker::cli`].

use clap::Parser;
use wave_tracker::cli::{Args, run};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    run(Args::parse())
}
