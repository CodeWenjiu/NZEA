use std::{cell::RefCell, char, io::Write, sync::OnceLock};

use logger::Logger;
use option_parser::OptionParser;
use owo_colors::OwoColorize;
use remu_macro::{log_debug, log_err, log_error};
use remu_utils::{ProcessError, ProcessResult};
use state::{reg::RegfileIo, States};

use crate::{SimulatorCallback, SimulatorError, SimulatorItem};

use super::{bounded_channel::BoundedChannel, BasicCallbacks, Input, NpcCallbacks, Output, Top};

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

    // channel for simulate
    static IFU2IDU_CHANNEL: BoundedChannel<(u32, u32)> = BoundedChannel::new(1);

    static IDU2ALU_CHANNEL: BoundedChannel<(u32, u32)> = BoundedChannel::new(1);
    static IDU2LSU_CHANNEL: BoundedChannel<(u32, u32)> = BoundedChannel::new(1);

    static ALU2WBU_CHANNEL: BoundedChannel<(u32, u32)> = BoundedChannel::new(1);
    static LSU2WBU_CHANNEL: BoundedChannel<(u32, u32)> = BoundedChannel::new(1);
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
    let pc = unsafe { &*pc };
    let inst = unsafe { &*inst };

    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().ifu_catch += 1;
    });

    IFU2IDU_CHANNEL.with(|channel| {
        if let Err(e) = channel.send((*pc, *inst)) {
            log_error!(format!("IFU Failed to send instruction: {}", e));
            nzea_result_write(Err(ProcessError::Recoverable));
        }
    });
}

unsafe extern "C" fn alu_catch_handler() {
    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().alu_catch += 1;
    });

    let res: ProcessResult<()> = (|| {
        let (pc, inst) = IDU2ALU_CHANNEL.with(|channel| {
            if let Some((pc, inst)) = channel.recv() {
                Ok((pc, inst))
            } else {
                log_error!("ALU Failed to receive instruction");
                Err(ProcessError::Recoverable)
            }
        })?;

        ALU2WBU_CHANNEL.with(|channel| {
            if let Err(e) = channel.send((pc, inst)) {
                log_error!(format!("ALU Failed to send instruction: {}", e));
                return Err(ProcessError::Recoverable);
            }
            Ok(())
        })?;

        Ok(())
    })();
    nzea_result_write(res);
}

unsafe extern "C" fn idu_catch_handler(inst_type: Input) {
    let inst_type = unsafe { &*inst_type };

    nzea_result_write(NZEA_TIME.with(|time| {
        let mut time = time.get().unwrap().borrow_mut();

        let (pc, inst) = match IFU2IDU_CHANNEL.with(|channel| {
            if let Some((pc, inst)) = channel.recv() {
                Ok((pc, inst))
            } else {
                log_error!("IDU Failed to receive instruction");
                Err(ProcessError::Recoverable)
            }
        }) {
            Ok(val) => val,
            Err(e) => return Err(e),
        };

        match inst_type {
            0 => {
                time.idu_catch_al += 1;
                if let Err(e) = IDU2ALU_CHANNEL.with(|channel| channel.send((pc, inst))) {
                    log_error!(format!("IDU Failed to send instruction: {}", e));
                    return Err(ProcessError::Recoverable);
                }
            },
            1 => {
                time.idu_catch_ls += 1;
                if let Err(e) = IDU2LSU_CHANNEL.with(|channel| channel.send((pc, inst))) {
                    log_error!(format!("IDU Failed to send instruction: {}", e));
                    return Err(ProcessError::Recoverable);
                }
            },
            2 => {
                time.idu_catch_cs += 1;
                if let Err(e) = IDU2ALU_CHANNEL.with(|channel| channel.send((pc, inst))) {
                    log_error!(format!("IDU Failed to send instruction: {}", e));
                    return Err(ProcessError::Recoverable);
                }
            },
            _ => {
                log_error!(format!("Unknown instruction type: {}", inst_type));
                return Err(ProcessError::Recoverable);
            }
        };

        Ok(())
    }));
}

unsafe extern "C" fn icache_mat_catch_handler(count: Input) {
    let count = unsafe { &*count };

    NZEA_TIME.with(|time| {
        time.get()
            .unwrap()
            .borrow_mut()
            .icache_average_memory_acess_cycle += *count as u64;
    });
}

unsafe extern "C" fn icache_catch_handler(map_hit: u8, cache_hit: u8) {
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
    log_debug!("icache_flush_p");
}

unsafe extern "C" fn icache_state_catch_handler(
    _write_index: Input,
    _write_way: Input,
    _write_tag: Input,
    _write_data: Input,
) {
    // log_debug!("icache_state_catch_p");
}

unsafe extern "C" fn lsu_catch_handler(diff_skip: u8) {
    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().ifu_catch += 1;
    });

    let res: ProcessResult<()> = (|| {
        let (pc, inst) = IDU2LSU_CHANNEL.with(|channel| {
            if let Some((pc, inst)) = channel.recv() {
                Ok((pc, inst))
            } else {
                log_error!("LSU Failed to receive instruction");
                Err(ProcessError::Recoverable)
            }
        })?;

        LSU2WBU_CHANNEL.with(|channel| {
            if let Err(e) = channel.send((pc, inst)) {
                log_error!(format!("LSU Failed to send instruction: {}", e));
                return Err(ProcessError::Recoverable);
            }
            Ok(())
        })?;
        
        Ok(())
    })();
    nzea_result_write(res);

    NZEA_CALLBACK.with(|callback| {
        let callback = callback.get().unwrap().borrow_mut();
        if diff_skip == 1 {
            (callback.difftest_skip)();
        }
    });
}

unsafe extern "C" fn pipeline_catch_handler() {
    // pipeline flushed, should clear the instruction queue
    // INSTRUCTION_MESSAGE_QUENE.with(|queue| {
    //     let mut queue = queue.borrow_mut();
    //     let last = *queue.last().unwrap();
    //     queue.clear();
    //     queue.push(last);
    // });
}

unsafe extern "C" fn uart_catch_handler(c: Input) {
    let c = unsafe { &char::from_u32(*c) };
    if let Some(c) = c {
        print!("{}", c);
        std::io::stdout().flush().unwrap();
    }
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
    let (next_pc, gpr_waddr, gpr_wdata) = unsafe { (&*next_pc, &*gpr_waddr, &*gpr_wdata) };
    let (csr_wena, csr_waddra, csr_wdataa) = unsafe { (&*csr_wena, &*csr_waddra, &*csr_wdataa) };
    let (csr_wenb, csr_waddrb, csr_wdatab) = unsafe { (&*csr_wenb, &*csr_waddrb, &*csr_wdatab) };

    nzea_result_write(NZEA_CALLBACK.with(|callback| {
        let mut callback = callback.get().unwrap().borrow_mut();
        let (pc, inst) = IDU2ALU_CHANNEL.with(|channel| {
            if let Some((pc, inst)) = channel.recv() {
                Ok((pc, inst))
            } else {
                log_error!("WBU Failed to receive instruction");
                Err(ProcessError::Recoverable)
            }
        })?;

        let reg_pc = NZEA_STATES.with(|s| s.get().unwrap().borrow().regfile.read_pc());
        if pc != reg_pc {
            log_error!(format!("PC mismatch: {:#08x} != {:#08x}", pc, reg_pc));
            return Err(ProcessError::Recoverable)
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

unsafe extern "C" fn sram_read_handler(addr: Input, data: Output) {
    let addr = unsafe { &(*addr & !0x3) };
    let data = unsafe { &mut *data };

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        *data = log_err!(
            states.mmu.read_memory(*addr, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

unsafe extern "C" fn sram_write_handler(_addr: Input, _data: Input, _mask: Input) {
    let (addr, data, mask) = unsafe { (&*_addr, &*_data, &*_mask) };

    let mut data = *data;
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
            return;
        }
    };

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        log_err!(
            states.mmu.write(*addr, data, mask),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

pub struct Nzea {
    pub top: Top,
}

impl Nzea {
    pub fn new(_option: &OptionParser, states: States, callback: SimulatorCallback) -> Self {
        let top = Top::new();

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

    fn function_wave_trace(&self,enable:bool) {
        self.top.function_wave_trace(enable);
    }
}
