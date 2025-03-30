use option_parser::OptionParser;
use remu_utils::ProcessResult;
use state::States;

use crate::{SimulatorCallback, SimulatorError, SimulatorItem};

use super::Top;

pub struct Nzea {
    pub top: Top,
    pub states: States,
    pub callback: SimulatorCallback,
}

impl Nzea {
    pub fn new(_option: &OptionParser, states: States, callback: SimulatorCallback) -> Self {
        let top = Top::new();

        Self {
            top,
            states,
            callback,
        }
    }
}

impl SimulatorItem for Nzea {
    fn init(&self) -> Result<(), SimulatorError> {
        self.top.reset(100);
        Ok(())
    }

    fn step_cycle(&mut self) -> ProcessResult<()> {
        self.top.cycle(1);
        Ok(())
    }
}
