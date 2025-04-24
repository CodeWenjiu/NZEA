use std::{cell::RefCell, char, io::Write, sync::OnceLock};

use logger::Logger;
use option_parser::OptionParser;
use owo_colors::OwoColorize;
use remu_macro::{log_err, log_error};
use remu_utils::{ProcessError, ProcessResult};
use state::{model::BasicPipeCell, reg::RegfileIo, States};

use crate::{SimulatorCallback, SimulatorError, SimulatorItem};

use super::{BasicCallbacks, Input, NpcCallbacks, Output, Top, YsyxsocCallbacks};

#[derive(Default)]
pub struct NzeaTimes {
    pub cycles: u64,
    pub instructions: u64,

    pub ifu_catch: u64,
    pub icache_map_miss: u64,
    pub icache_cache_miss: u64,
    pub icache_cache_hit: u64,
    pub icache_average_memory_acess_cycle: u64,

    pub idu_catch_al: u64,
    pub idu_catch_ls: u64,
    pub idu_catch_cs: u64,

    pub alu_catch: u64,
}

thread_local! {
    static NZEA_STATES : OnceLock<RefCell<States>> = OnceLock::new();
    static NZEA_CALLBACK : OnceLock<RefCell<SimulatorCallback>> = OnceLock::new();
    static NZEA_TIME : OnceLock<RefCell<NzeaTimes>> = OnceLock::new();
    static NZEA_RESULT : OnceLock<RefCell<ProcessResult<()>>> = OnceLock::new();
}

fn nzea_result_write(result: ProcessResult<()>) {
    NZEA_RESULT.with(|result_ref| {
        let mut res = result_ref.get().unwrap().borrow_mut();
        if res.is_err() {
            return;
        }
        *res = result;
    });
}

unsafe extern "C" fn ifu_catch_handler(pc: Input, inst: Input) {
    // log_debug!("ifu_catch_p");

    let pc = unsafe { &*pc };
    let inst = unsafe { &*inst };

    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().ifu_catch += 1;
    });

    nzea_result_write(
        NZEA_STATES.with(|state| {
            state.get().unwrap().borrow_mut().pipe_state.send((*pc, *inst), BasicPipeCell::IDU)
        })
    );
}

unsafe extern "C" fn alu_catch_handler(pc: Input) {
    // log_debug!("alu_catch_p");
    let pc = unsafe { &*pc };

    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().alu_catch += 1;
    });


    nzea_result_write(NZEA_STATES.with(|state| {
        let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;
        pipe_state.trans(BasicPipeCell::ALU, BasicPipeCell::WBU); // Consider adding '?' if trans returns Result
        let (fetched_pc, _) = pipe_state.fetch(BasicPipeCell::ALU)?;
        if fetched_pc != *pc {
            log_error!(format!("ALU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
            return Err(ProcessError::Recoverable);
        }
        Ok(())
    }));
}

unsafe extern "C" fn idu_catch_handler(pc: Input, inst_type: Input) {
    // log_debug!("idu_catch_p");

    let pc = unsafe { &*pc };
    let inst_type = unsafe { &*inst_type };

    nzea_result_write(NZEA_TIME.with(|time| {
        let mut time = time.get().unwrap().borrow_mut();

        NZEA_STATES.with(|state| {
            let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;

            match inst_type {
                0 => {
                    time.idu_catch_al += 1;
                    pipe_state.trans(BasicPipeCell::IDU, BasicPipeCell::ALU);
                }
                1 => {
                    time.idu_catch_ls += 1;
                    pipe_state.trans(BasicPipeCell::IDU, BasicPipeCell::LSU);
                }
                2 => {
                    time.idu_catch_cs += 1;
                    pipe_state.trans(BasicPipeCell::IDU, BasicPipeCell::ALU);
                }
                _ => {
                    log_error!(format!("Unknown instruction type: {}", inst_type));
                    return Err(ProcessError::Recoverable);
                }
            };

            let (fetched_pc, _) = pipe_state.fetch(BasicPipeCell::IDU)?;

            if fetched_pc != *pc {
                log_error!(format!("IDU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
                return Err(ProcessError::Recoverable);
            }
            
            Ok(())
        })
    }));
}

unsafe extern "C" fn icache_mat_catch_handler(count: Input) {
    // log_debug!("icache_mat_catch_p");
    let count = unsafe { &*count };

    NZEA_TIME.with(|time| {
        time.get()
            .unwrap()
            .borrow_mut()
            .icache_average_memory_acess_cycle += *count as u64;
    });
}

unsafe extern "C" fn icache_catch_handler(map_hit: u8, cache_hit: u8) {
    // log_debug!("icache_catch_p");
    NZEA_TIME.with(|time| {
        let mut time = time.get().unwrap().borrow_mut();

        if map_hit == 0 {
            time.icache_map_miss += 1;
            return;
        }

        if cache_hit == 0 {
            time.icache_cache_miss += 1;
            return;
        }

        time.icache_cache_hit += 1;
    });
}

unsafe extern "C" fn icache_flush_handler() {
    // log_debug!("icache_flush_p");
}

unsafe extern "C" fn icache_state_catch_handler(
    _write_index: Input,
    _write_way: Input,
    _write_tag: Input,
    _write_data: Input,
) {
    // log_debug!("icache_state_catch_p");
}

unsafe extern "C" fn lsu_catch_handler(pc: Input, diff_skip: u8) {
    // log_debug!("lsu_catch_p");

    let pc = unsafe { &*pc };
    nzea_result_write(
        NZEA_STATES.with(|state| {
            let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;
            let (fetched_pc, _) = pipe_state.fetch(BasicPipeCell::LSU)?;
            if fetched_pc != *pc {
                log_error!(format!("LSU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
                return Err(ProcessError::Recoverable);
            }
            Ok(())
        })
    );

    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().ifu_catch += 1;
    });

    NZEA_STATES.with(|state| {
        state.get().unwrap().borrow_mut().pipe_state.trans(BasicPipeCell::LSU, BasicPipeCell::WBU);
    });

    NZEA_CALLBACK.with(|callback| {
        let callback = callback.get().unwrap().borrow_mut();
        if diff_skip == 1 {
            (callback.difftest_skip)();
        }
    });
}

unsafe extern "C" fn pipeline_catch_handler() {
    // log_debug!("flush_p");
    NZEA_STATES.with(|state| {
        state.get().unwrap().borrow_mut().pipe_state.flush();
    });
}

unsafe extern "C" fn wbu_catch_handler(
    next_pc: Input,
    gpr_waddr: Input,
    gpr_wdata: Input,
    csr_wena: Input,
    csr_waddra: Input,
    csr_wdataa: Input,
    csr_wenb: Input,
    csr_waddrb: Input,
    csr_wdatab: Input,
) {
    // log_debug!("wbu_catch_p");

    let (next_pc, gpr_waddr, gpr_wdata) = unsafe { (&*next_pc, &*gpr_waddr, &*gpr_wdata) };
    let (csr_wena, csr_waddra, csr_wdataa) = unsafe { (&*csr_wena, &*csr_waddra, &*csr_wdataa) };
    let (csr_wenb, csr_waddrb, csr_wdatab) = unsafe { (&*csr_wenb, &*csr_waddrb, &*csr_wdatab) };

    nzea_result_write(NZEA_CALLBACK.with(|callback| {
        let mut callback = callback.get().unwrap().borrow_mut();

        let (pc, inst) = NZEA_STATES.with(|state| {
            let data = state.get().unwrap().borrow_mut().pipe_state.get()?;
            Ok(data)
        })?;

        if inst == 0b00000000000100000000000001110011 {
            (callback.trap)(NZEA_STATES.with(|s| {
                s.get().unwrap().borrow().regfile.read_gpr(10).unwrap() == 0
            }));
            return Err(ProcessError::Recoverable);
        }

        let reg_pc = NZEA_STATES.with(|s| s.get().unwrap().borrow().regfile.read_pc());
        if pc != reg_pc {
            log_error!(format!("PC mismatch: {:#08x} != {:#08x}", pc, reg_pc));
            return Err(ProcessError::Recoverable);
        }

        NZEA_STATES.with(|states| {
            let mut states = states.get().unwrap().borrow_mut();
            states.regfile.write_pc(*next_pc);
            states
                .regfile
                .write_gpr(*gpr_waddr, *gpr_wdata)
                .map_err(|_| ProcessError::Recoverable)?;
            if *csr_wena == 1 {
                states
                    .regfile
                    .write_csr(*csr_waddra, *csr_wdataa)
                    .map_err(|_| ProcessError::Recoverable)?
            };
            if *csr_wenb == 1 {
                states
                    .regfile
                    .write_csr(*csr_waddrb, *csr_wdatab)
                    .map_err(|_| ProcessError::Recoverable)?
            };

            Ok(())
        })?;

        NZEA_TIME.with(|times| {
            let mut time = times.get().unwrap().borrow_mut();
            time.instructions += 1;
        });

        (callback.instruction_compelete)(pc, inst)
    }));
}

// npc callback
fn read_mask_trans(data: u32, mask: u32) -> (u32, state::mmu::Mask) {
    let mut data = data;
    let mask = match mask {
        0b0001 => state::mmu::Mask::Byte,
        0b0010 => {
            data = data.wrapping_shr(8);
            state::mmu::Mask::Byte
        }
        0b0100 => {
            data = data.wrapping_shr(16);
            state::mmu::Mask::Byte
        }
        0b1000 => {
            data = data.wrapping_shr(24);
            state::mmu::Mask::Byte
        }

        0b0011 => state::mmu::Mask::Half,
        0b1100 => {
            data = data.wrapping_shr(16);
            state::mmu::Mask::Half
        }

        0b1111 => state::mmu::Mask::Word,

        _ => {
            log_error!(format!("Unknown mask: {}", mask));
            nzea_result_write(Err(ProcessError::Recoverable));
            return (data, state::mmu::Mask::Word);
        }
    };
    (data, mask)
}

fn write_mask_trans(len: u32) -> state::mmu::Mask {
    match len {
        1 => state::mmu::Mask::Byte,
        2 => state::mmu::Mask::Half,
        4 => state::mmu::Mask::Word,
        _ => {
            log_error!(format!("Unknown mask: {}", len));
            nzea_result_write(Err(ProcessError::Recoverable));
            state::mmu::Mask::Word
        }
    }
}

fn read_by_name(name: &str, addr: u32, data: *mut u32) {
    let addr = addr & !0x3;

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        unsafe { *data = log_err!(
            states.mmu.read_by_name(name, addr, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )? };
        Ok(())
    }));
}

fn write_by_name(name: &str, addr: u32, data: u32, len: u32) {
    let mask = write_mask_trans(len);
    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        log_err!(
            states.mmu.write_by_name(name, addr, data, mask),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

fn write_by_addr(addr: Input, data: Input, mask: Input) {
    let (addr, data, mask) = unsafe { (&*addr, &*data, &*mask) };

    let data = *data;
    let mask = *mask;
    let (data, mask) = read_mask_trans(data, mask);

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        log_err!(
            states.mmu.write(*addr, data, mask),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

unsafe extern "C" fn uart_catch_handler(c: Input) {
    let c = unsafe { &char::from_u32(*c) };
    if let Some(c) = c {
        print!("{}", c);
        std::io::stdout().flush().unwrap();
    }
}

unsafe extern "C" fn sram_read_handler(addr: Input, data: Output) {
    let addr = unsafe { *addr & !0x3 };
    // log_debug!(format!("sram_read addr: {:#08x}", addr));
    let data = unsafe { &mut *data };

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        *data = log_err!(
            states.mmu.read_memory(addr, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

unsafe extern "C" fn sram_write_handler(_addr: Input, _data: Input, _mask: Input) {
    write_by_addr(_addr, _data, _mask);
}

// ysyxsoc callback

unsafe extern "C" fn flash_read_handler(addr: u32, data: *mut u32) {
    read_by_name("FLASH", addr, data);
}

unsafe extern "C" fn mrom_read_handler(addr: u32, data: *mut u32) {
    read_by_name("MROM", addr, data);
}

unsafe extern "C" fn psram_write_handler(addr: u32, data: u32, len: u32) {
    let mut data = data;
    match len {
        2 => {
            data = data.wrapping_shl(8);
        }
        4 => {
            data = data.wrapping_shl(16);
        }
        _ => {}
    }
    write_by_name("PSRAM", addr, data, len);
}

unsafe extern "C" fn psram_read(addr: u32, data: *mut u32) {
    read_by_name("PSRAM", addr, data);
}

unsafe extern "C" fn sdram_write_handler(addr: u32, data: u32, len: u32) {
    write_by_name("SDRAM", addr, data, len);
}

unsafe extern "C" fn sdram_read(addr: u32, data: *mut u32) {
    read_by_name("SDRAM", addr, data);
}

unsafe extern "C" fn vga_write_handler(addr: u32, data: u32) {
    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        log_err!(
            states.mmu.write(addr, data, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

unsafe extern "C" fn vga_read(xaddr: u32, yaddr: u32, data: *mut u32) {
    let addr = (yaddr * 640 + xaddr) * 4;
    
    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        unsafe { *data = log_err!(
            states.mmu.read_memory(addr, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )? };
        Ok(())
    }));
}

pub struct Nzea {
    pub top: Top,
}

impl Nzea {
    pub fn new(option: &OptionParser, states: States, callback: SimulatorCallback) -> Self {
        let top = Top::new(option);

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

        let ysyxsoc_callbacks = YsyxsocCallbacks {
            flash_read: flash_read_handler,
            mrom_read: mrom_read_handler,
            psram_write: psram_write_handler,
            psram_read: psram_read,
            sdram_write: sdram_write_handler,
            sdram_read: sdram_read,
            vga_write: vga_write_handler,
            vga_read: vga_read,
        };

        top.init(basic_callbacks, npc_callbacks, ysyxsoc_callbacks);

        NZEA_STATES.with(|states_ref| {
            states_ref.get_or_init(|| std::cell::RefCell::new(states));
        });

        NZEA_CALLBACK.with(|callback_ref| {
            callback_ref.get_or_init(|| std::cell::RefCell::new(callback));
        });

        NZEA_TIME.with(|time_ref| {
            time_ref.get_or_init(|| std::cell::RefCell::new(NzeaTimes::default()));
        });

        NZEA_RESULT.with(|result_ref| {
            result_ref.get_or_init(|| std::cell::RefCell::new(Ok(())));
        });

        Self { top }
    }
}

impl SimulatorItem for Nzea {
    fn init(&self) -> Result<(), SimulatorError> {
        self.top.reset(100);
        Ok(())
    }

    fn step_cycle(&mut self) -> ProcessResult<()> {
        self.top.cycle(1);

        NZEA_STATES.with(|state| -> ProcessResult<()> {
            state.get().unwrap().borrow_mut().pipe_state.update()?;
            Ok(())
        })?;

        NZEA_TIME.with(|times| {
            let mut time = times.get().unwrap().borrow_mut();
            time.cycles += 1;
        });

        NZEA_RESULT.with(|result| {
            let clone = result.get().unwrap().borrow_mut().clone();
            *result.get().unwrap().borrow_mut() = Ok(());
            clone
        })
    }

    fn times(&self) -> ProcessResult<()> {
        NZEA_TIME.with(|time| {
            let time = time.get().unwrap().borrow();

            println!("{}: {}", "Cycles\t".purple(), time.cycles.blue());
            println!(
                "{}: {}",
                "Instructions\t".purple(),
                time.instructions.blue()
            );

            println!("{}: {}", "ALU\t".purple(), time.alu_catch.blue());

            println!("{}: {}", "IDU_AL\t".purple(), time.idu_catch_al.blue());
            println!("{}: {}", "IDU_LS\t".purple(), time.idu_catch_ls.blue());
            println!("{}: {}", "IDU_CS\t".purple(), time.idu_catch_cs.blue());

            println!("{}: {}", "IFU\t".purple(), time.ifu_catch.blue());

            println!(
                "{}: {}",
                "ICache Hit rate[In region]\t".purple(),
                (time.icache_cache_hit as f64
                    / (time.icache_cache_hit + time.icache_cache_miss) as f64)
                    .blue()
            );
            println!(
                "{}: {}",
                "ICache Miss rate[Out region]\t".purple(),
                (time.icache_cache_hit as f64
                    / (time.icache_cache_hit + time.icache_cache_miss + time.icache_map_miss)
                        as f64)
                    .blue()
            );
        });

        Ok(())
    }

    fn function_wave_trace(&self, enable: bool) {
        self.top.function_wave_trace(enable);
    }

    fn function_nvboard(&self, enable:bool) {
        self.top.function_nvboard(enable);
    }
}
