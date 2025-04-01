use std::sync::OnceLock;

use option_parser::OptionParser;
use owo_colors::OwoColorize;
use remu_macro::log_debug;
use logger::Logger;
use remu_utils::ProcessResult;
use state::States;

use crate::{SimulatorCallback, SimulatorError, SimulatorItem};

use super::{BasicCallbacks, Input, NpcCallbacks, Output, Top};

pub struct NzeaTimes {
    pub cycles: u64,
    pub instructions: u64,

    pub alu_catch: u64,
    pub idu_catch: u64,
    pub ifu_catch: u64,

    pub icache_map_miss: u64,
    pub icache_cache_miss: u64,
    pub icache_cache_hit: u64,
}

static mut NZEA_TIMES: NzeaTimes = NzeaTimes {
    cycles: 0,
    instructions: 0,

    alu_catch: 0,
    idu_catch: 0,
    ifu_catch: 0,

    icache_map_miss: 0,
    icache_cache_miss: 0,
    icache_cache_hit: 0,
};

thread_local! {
    static NZEA_MMU: OnceLock<States> = OnceLock::new();
}

unsafe extern "C" fn alu_catch_handler() {
    unsafe { NZEA_TIMES.alu_catch += 1 };
}

unsafe extern "C" fn idu_catch_handler(_inst_type: Input) {
    log_debug!("idu_catch_p");
}

unsafe extern "C" fn ifu_catch_handler(_pc: Input, _inst: Input) {
    log_debug!("ifu_catch_p");
}

unsafe extern "C" fn icache_mat_catch_handler(_count: Input) {
    log_debug!("icache_mat_catch_p");
}

unsafe extern "C" fn icache_catch_handler(_map_hit: u8, _cache_hit: u8) {
    log_debug!("icache_catch_p");
}

unsafe extern "C" fn icache_flush_handler() {
    log_debug!("icache_flush_p");
}

unsafe extern "C" fn icache_state_catch_handler(
    _write_index: Input,
    _write_way: Input,
    _write_tag: Input,
    _write_data: Input,
) {
    log_debug!("icache_state_catch_p");
}

unsafe extern "C" fn lsu_catch_handler(_diff_skip: u8) {
    log_debug!("lsu_catch_p");
}

unsafe extern "C" fn pipeline_catch_handler() {
    log_debug!("pipeline_catch_p");
}

unsafe extern "C" fn uart_catch_handler(_c: Input) {
    log_debug!("uart_catch");
}

unsafe extern "C" fn wbu_catch_handler(
    _next_pc: Input,
    _gpr_waddr: Input,
    _gpr_wdata: Input,
    _csr_wena: Input,
    _csr_waddra: Input,
    _csr_wdataa: Input,
    _csr_wenb: Input,
    _csr_waddrb: Input,
    _csr_wdatab: Input,
) {
    log_debug!("wbu_catch");
}

unsafe extern "C" fn sram_read_handler(_addr: Input, _data: Output) {
    log_debug!("sram_read");
}

unsafe extern "C" fn sram_write_handler(_addr: Input, _data: Input, _mask: Input) {
    log_debug!("sram_write");
}

pub struct Nzea {
    pub top: Top,
    pub callback: SimulatorCallback,
}

impl Nzea {
    pub fn new(_option: &OptionParser, states: States, callback: SimulatorCallback) -> Self {
        let top = Top::new();
        NZEA_MMU.with(|mmu| {
            let _ = mmu.set(states);
        });

        let basic_callbacks = BasicCallbacks {
            alu_catch_p: alu_catch_handler,
            idu_catch_p: idu_catch_handler,
            ifu_catch_p: ifu_catch_handler,
            icache_mat_catch_p: icache_mat_catch_handler,
            icache_catch_p: icache_catch_handler,
            icache_flush_p: icache_flush_handler,
            icache_state_catch_p: icache_state_catch_handler,
            lsu_catch_p: lsu_catch_handler,
            pipeline_catch_p: pipeline_catch_handler,
            wbu_catch: wbu_catch_handler,
        };
        
        let npc_callbacks = NpcCallbacks {
            uart_catch: uart_catch_handler,
            sram_read: sram_read_handler,
            sram_write: sram_write_handler,
        };

        top.init(basic_callbacks, npc_callbacks);

        Self {
            top,
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

    fn times(&self) -> ProcessResult<()> {
        println!("{}: {}", "Cycles\t".purple(), NZEA_TIMES.cycles.blue());
        println!("{}: {}", "Instructions\t".purple(), NZEA_TIMES.instructions.blue());
        println!("{}: {}", "ALU\t".purple(), NZEA_TIMES.alu_catch.blue());
        println!("{}: {}", "IDU\t".purple(), NZEA_TIMES.idu_catch.blue());
        println!("{}: {}", "IFU\t".purple(), NZEA_TIMES.ifu_catch.blue());

        println!("{}: {}", "ICache Hit rate[In region]\t".purple(), (NZEA_TIMES.icache_cache_hit as f64 / (NZEA_TIMES.icache_cache_hit + NZEA_TIMES.icache_cache_miss) as f64).blue());
        println!("{}: {}", "ICache Miss rate[Out region]\t".purple(), (NZEA_TIMES.icache_cache_hit as f64 / (NZEA_TIMES.icache_cache_hit + NZEA_TIMES.icache_cache_miss + NZEA_TIMES.icache_map_miss) as f64).blue());
        Ok(())
    }
}
