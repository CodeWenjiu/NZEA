use std::{cell::RefCell, char, fs::File, io::{BufRead, BufReader, Write}, process::Command, sync::OnceLock, time::Instant, vec};

use comfy_table::{Cell, Table};
use option_parser::OptionParser;
use pest::Parser;
use remu_macro::{log_err, log_error, log_info};
use remu_utils::{ProcessError, ProcessResult, Simulators};
use state::{model::BaseStageCell, reg::RegfileIo, States};

use crate::{nzea::get_nzea_root, SimulatorCallback, SimulatorError, SimulatorItem};

use super::{BasicCallbacks, Input, JydRemoteCallbacks, NpcCallbacks, Output, Top, YsyxsocCallbacks};

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

use pest_derive::Parser;
#[derive(Parser)]
#[grammar = "nzea/src/area_parser.pest"]
struct AreaParser;

impl NzeaTimes {
    fn get_freq(file: File) -> ProcessResult<String> {
        let file = BufReader::new(file);
        let lines: Vec<String> = file.lines()
            .map(|line| 
                line.map_err(|e| {
                    log_error!(format!("Failed to read line: {}", e));
                    ProcessError::Recoverable
                })
            )
            .collect::<Result<_, _>>()?;
        
        let mut lines = lines.into_iter();

        // Skip first two lines
        for _ in 0..2 {
            lines.next();
        }

        // Read header line
        let header_line = lines.next().unwrap();
        let freq_index = header_line.split('|')
            .enumerate()
            .find(|(_, field)| field.trim() == "Freq(MHz)")
            .ok_or_else(|| {
                log_error!("Freq(MHz) column not found in header");
                ProcessError::Recoverable
            })?.0;

        // Read data lines
        let mut data_line = String::new();
        for _ in 0..2 {
            data_line = lines.next().unwrap();
        }

        let freq_value = data_line.split('|')
            .nth(freq_index)
            .unwrap()
            .trim()
            .to_string();

        Ok(freq_value)
    }    

    fn get_area(file: File) -> ProcessResult<String> {
        let string = BufReader::new(file)
            .lines()
            .collect::<Result<Vec<String>, _>>()
            .map_err(|e| {
                log_error!(format!("Failed to read line: {}", e));
                ProcessError::Recoverable
            })?
            .join("\n");
        let pairs = 
            log_err!(AreaParser::parse(Rule::file, &string), ProcessError::Recoverable)?;
            
        for pair in pairs {
            match pair.as_rule() {
                Rule::EOI => {
                    continue;
                }

                Rule::float => {
                    let area = pair.as_str().to_string();
                    return Ok(area);
                }

                _ => unreachable!("WTF")
            }
        }

        log_error!("Chip area not found in synth_stat.txt");
        Err(ProcessError::Recoverable)
    }

    pub fn print(&self, target: &str) -> ProcessResult<()> {
        let nzea_root = get_nzea_root();

        let output = Command::new("git")
            .args(["rev-parse", "--short", "HEAD"])
            .current_dir(get_nzea_root().parent().unwrap())
            .output()
            .map_err(|_| {
                log_error!("Failed to get git commit hash");
                ProcessError::Recoverable
            })?;
        
        if !output.status.success() {
            log_error!("Failed to get git commit hash");
            return Err(ProcessError::Recoverable);
        }
        
        let commit_hash = String::from_utf8_lossy(&output.stdout).trim().to_string();

        // synthetic top
        let (freq, area) = if target == "Npc" {
            ("N/A".to_string(), "N/A".to_string())
        } else {

            let target_lower = target.to_string().to_lowercase();

            log_info!("Synthetic NZEA...");

            let output = Command::new("sh")
                .arg("-c")
                .arg(format!("make -C {} syn PLATFORM={}", nzea_root.display(), target_lower))
                .output()
                .expect("Failed to execute synthetic command");

            if !output.status.success() {
                eprintln!("stderr: {}", String::from_utf8_lossy(&output.stderr));
                eprintln!("stdout: {}", String::from_utf8_lossy(&output.stdout));
                log_error!("Failed to synthesize NZEA");
                return Err(ProcessError::Recoverable);
            }

            let result_path = nzea_root
                .join("build")
                .join(format!("{target_lower}_core"))
                .join("result");

            let stat_file = result_path.join("synth_stat.txt");
            let report_file = result_path.join("top.rpt");

            let stat = File::open(stat_file)
                .map_err(|_| {
                    log_error!("Failed to open synth_stat.txt");
                    ProcessError::Recoverable
                })?;
            
            let report = File::open(report_file)
                .map_err(|_| {
                    log_error!("Failed to read top.rpt");
                    ProcessError::Recoverable
                })?;

            log_info!("NZEA synthesized successfully");
            
            (Self::get_freq(report)?, Self::get_area(stat)?)
        };

        let mut table = Table::new();

        table
            .add_row(vec![
                Cell::new("Commit Hash").fg(comfy_table::Color::Blue),
                Cell::new("Area(um^2)").fg(comfy_table::Color::Blue),
                Cell::new("IPC").fg(comfy_table::Color::Blue),
                Cell::new("Freq(MHz)").fg(comfy_table::Color::Blue),
                Cell::new("Cycles").fg(comfy_table::Color::Blue),
            ])
            .add_row(vec![
                Cell::new(commit_hash).fg(comfy_table::Color::Green),
                Cell::new(format!("{}", area)).fg(comfy_table::Color::Green),
                Cell::new(format!("{:.6}", self.instructions as f64 / self.cycles as f64)).fg(comfy_table::Color::Green),
                Cell::new(format!("{}", freq)).fg(comfy_table::Color::Green),
                Cell::new(format!("{}", self.cycles)).fg(comfy_table::Color::Green),
            ]);

        println!("{table}");

        Ok(())
    }
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

unsafe extern "C" fn bpu_catch_handler(pc: Input) {
    let pc = unsafe { &*pc };

    nzea_result_write(
        NZEA_STATES.with(|state| {
            state.get().unwrap().borrow_mut().pipe_state.cell_input((*pc, 0), BaseStageCell::BpIf)
        })
    );

    NZEA_CALLBACK.with(|callback| {
        let callback = callback.get().unwrap().borrow_mut();
        (callback.branch_predict)();
    });
}

unsafe extern "C" fn btb_cache_access_p(is_replace: bool, set: u8, way: bool, tag: u32, data: u32) {
    let _ = (is_replace, set, way, tag, data);
}

unsafe extern "C" fn ifu_catch_handler(pc: Input, inst: Input) {
    // log_debug!("ifu_catch_p");

    let _pc = unsafe { &*pc };
    let inst = unsafe { &*inst };

    NZEA_TIME.with(|time| {
        time.get().unwrap().borrow_mut().ifu_catch += 1;
    });

    nzea_result_write(
        NZEA_STATES.with(|state| {
            // state.get().unwrap().borrow_mut().pipe_state.cell_input((*pc, *inst), BaseStageCell::IfId)
            state.get().unwrap().borrow_mut().pipe_state.instruction_fetch(*inst)
        })
    );

    NZEA_CALLBACK.with(|callback| {
        let callback = callback.get().unwrap().borrow_mut();
        (callback.instruction_fetch)();
    });
}

unsafe extern "C" fn idu_catch_handler(pc: Input) {
    // log_debug!("idu_catch_p");

    let pc = unsafe { &*pc };

    nzea_result_write(
         NZEA_STATES.with(|state| {
            
            state.get().unwrap().borrow_mut().pipe_state.trans(BaseStageCell::IfId, BaseStageCell::IdIs)?;

            let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;

            let (fetched_pc, _) = pipe_state.fetch(BaseStageCell::IfId)?;

            if fetched_pc != *pc {
                log_error!(format!("IDU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
                return Err(ProcessError::Recoverable);
            }
            
            Ok(())
        })
    );
}

unsafe extern "C" fn isu_catch_handler(pc: Input, inst_type: u8) {
    // log_debug!("isu_catch_p");
    let pc = unsafe { &*pc };

    nzea_result_write(
        NZEA_STATES.with(|state| {
           let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;

           match inst_type {
               0 => {
                   pipe_state.trans(BaseStageCell::IdIs, BaseStageCell::IsLs)?;
               }
               1 => {
                   pipe_state.trans(BaseStageCell::IdIs, BaseStageCell::IsAl)?;
               }
               _ => {
                   log_error!(format!("Unknown instruction type: {}", inst_type));
                   return Err(ProcessError::Recoverable);
               }
           };

           let (fetched_pc, _) = pipe_state.fetch(BaseStageCell::IdIs)?;

           if fetched_pc != *pc {
               log_error!(format!("IDU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
               return Err(ProcessError::Recoverable);
           }
           
           Ok(())
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
        pipe_state.trans(BaseStageCell::IsAl, BaseStageCell::ExWb)?; 
        let (fetched_pc, _) = pipe_state.fetch(BaseStageCell::IsAl)?;
        if fetched_pc != *pc {
            log_error!(format!("ALU catch PC mismatch: fetched {:#08x}, expected {:#08x}", fetched_pc, pc));
            return Err(ProcessError::Recoverable);
        }
        Ok(())
    }));
}

unsafe extern "C" fn lsu_catch_handler(pc: Input, diff_skip: u8, skip_val: Input) {
    // log_debug!("lsu_catch_p");

    let pc = unsafe { &*pc };
    nzea_result_write(
        NZEA_STATES.with(|state| {
            let pipe_state = &mut state.get().unwrap().borrow_mut().pipe_state;
            pipe_state.trans(BaseStageCell::IsLs, BaseStageCell::ExWb)?;
            let (fetched_pc, _) = pipe_state.fetch(BaseStageCell::IsLs)?;
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

    NZEA_CALLBACK.with(|callback| {
        let callback = callback.get().unwrap().borrow_mut();
        if diff_skip == 1 {
            let skip_val = unsafe { *skip_val };
            (callback.difftest_skip)(skip_val);
        }
        (callback.load_store)();
    });
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

unsafe extern "C" fn pipeline_catch_handler() {
    NZEA_STATES.with(|state| {
        state.get().unwrap().borrow_mut().pipe_state.flush();
    });
}

unsafe extern "C" fn wbu_catch_handler(
    next_pc: Input,
    gpr_waddr: Input,
    gpr_wdata: Input,
    csr_wen: u8,
    csr_waddr: Input,
    csr_wdata: Input,
    is_trap: u8,
    trap_type: Input,
) {
    // log_debug!("wbu_catch_p");

    let (next_pc, gpr_waddr, gpr_wdata) = unsafe { (&*next_pc, &*gpr_waddr, &*gpr_wdata) };
    let (csr_wen, csr_waddr, csr_wdata) = unsafe { (&csr_wen, &*csr_waddr, &*csr_wdata) };
    let (is_trap, trap_type) = unsafe { (&is_trap, &*trap_type) };

    nzea_result_write(NZEA_CALLBACK.with(|callback| {
        let mut callback = callback.get().unwrap().borrow_mut();

        let (pc, inst) = NZEA_STATES.with(|state| {
            let data = state.get().unwrap().borrow_mut().pipe_state.get()?;
            Ok(data)
        })?;

        if inst == 0b00000000000100000000000001110011 {
            (callback.yield_)();
            return Err(ProcessError::Recoverable);
        }

        let reg_pc = NZEA_STATES.with(|s| s.get().unwrap().borrow().regfile.read_pc());
        if pc != reg_pc {
            log_error!(format!("PC mismatch: Pipeline: {:#08x} != State: {:#08x}", pc, reg_pc));
            return Err(ProcessError::Recoverable);
        }

        NZEA_STATES.with(|states| {
            let mut states = states.get().unwrap().borrow_mut();

            states.regfile.write_pc(*next_pc);

            if *is_trap == 1 {
                let trap_type = *trap_type;
                states.regfile.trap(pc, trap_type)?;
                return Ok(())
            }

            states
                .regfile
                .write_gpr(*gpr_waddr, *gpr_wdata)
                .map_err(|_| ProcessError::Recoverable)?;

            if *csr_wen == 1 {
                states
                    .regfile
                    .write_csr(*csr_waddr, *csr_wdata)
                    .map_err(|_| ProcessError::Recoverable)?
            };

            Ok(())
        })?;

        NZEA_TIME.with(|times| {
            let mut time = times.get().unwrap().borrow_mut();
            time.instructions += 1;
        });

        (callback.instruction_complete)(pc, *next_pc, inst)
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
            {states.mmu.read_by_name(name, addr, state::mmu::Mask::Word)},
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
    let mut buffer: u32 = 0;
    read_by_name("FLASH", addr, &mut buffer);
    unsafe { *data = buffer.swap_bytes(); }
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
    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        unsafe { *data = log_err!(
            {states.mmu.read_by_name("SDRAM", addr, state::mmu::Mask::Half)},
            ProcessError::Recoverable
        )? };
        Ok(())
    }));
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
    
    read_by_name("VGA", addr, data);
}

// jyd remote callback

unsafe extern "C" fn irom_read_handler(addr: Input, data: Output) {
    let addr = unsafe { *addr };
    let data = unsafe { &mut *data };

    if !(0x8000_0000..0x8000_3fff).contains(&addr) {
        return;
    }

    nzea_result_write(NZEA_STATES.with(|states| {
        let mut states = states.get().unwrap().borrow_mut();
        *data = log_err!(
            states.mmu.read_memory(addr, state::mmu::Mask::Word),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

static TIMER: OnceLock<Instant> = OnceLock::new();

unsafe extern "C" fn dram_read_handler(addr: Input, mask: Input, data: Output) {
    let addr = unsafe { *addr };
    let data = unsafe { &mut *data };
    let mask = unsafe { *mask };

    if addr == 0x8014_0000 {
        return;
    }

    if addr == 0x8020_0050 {
        let timer = TIMER.get().unwrap();
        let elapsed = timer.elapsed();
        *data = elapsed.as_micros() as u32;
    }

    if !(0x8010_0000..=0x801f_ffff).contains(&addr) && !(0x8000_0000..=0x8000_3fff).contains(&addr) {
        return;
    }

    nzea_result_write(NZEA_STATES.with(|states| {
        let mask = match mask {
            0 => state::mmu::Mask::Byte,
            1 => state::mmu::Mask::Half,
            2 => state::mmu::Mask::Word,
            _ => {
                log_error!(format!("Unknown mask: {}", mask));
                nzea_result_write(Err(ProcessError::Recoverable));
                return Ok(());
            }
        };
        let mut states = states.get().unwrap().borrow_mut();
        *data = log_err!(
            states.mmu.read_memory(addr, mask),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

unsafe extern "C" fn dram_write_handler(addr: Input, mask: Input, data: Input) {
    let addr = unsafe { *addr };
    let data = unsafe { *data };
    let mask = unsafe { *mask };

    if addr == 0x8014_0000 {
        let c = &char::from_u32(data);
        if let Some(c) = c {
            print!("{}", c);
            std::io::stdout().flush().unwrap();
        }
        return;
    }

    if addr == 0x8020_0050 {
        // 定时器
        use std::time::Instant;

        match data {
            0x8000_0000 => { // 启动定时器
                let _timer = TIMER.get_or_init(|| Instant::now());
            }

            0xffff_ffff => { // 停止定时器
            }

            _ => ()
        }
        return;
    }

    if !(0x8010_0000..0x801f_ffff).contains(&addr) {
        return;
    }

    nzea_result_write(NZEA_STATES.with(|states| {
        let mask = match mask {
            0 => state::mmu::Mask::Byte,
            1 => state::mmu::Mask::Half,
            2 => state::mmu::Mask::Word,
            _ => {
                log_error!(format!("Unknown mask: {}", mask));
                nzea_result_write(Err(ProcessError::Recoverable));
                return Ok(());
            }
        };
        let mut states = states.get().unwrap().borrow_mut();
        log_err!(
            states.mmu.write(addr, data, mask),
            ProcessError::Recoverable
        )?;
        Ok(())
    }));
}

pub struct Nzea {
    target: Simulators,

    pub top: Top,
}

impl Nzea {
    pub fn new(option: &OptionParser, states: States, callback: SimulatorCallback) -> Self {
        let top = Top::new(option);

        let basic_callbacks = BasicCallbacks {
            bpu_catch_p: bpu_catch_handler,
            btb_cache_access_p: btb_cache_access_p,
            ifu_catch_p: ifu_catch_handler,
            icache_mat_catch_p: icache_mat_catch_handler,
            icache_catch_p: icache_catch_handler,
            icache_flush_p: icache_flush_handler,
            icache_state_catch_p: icache_state_catch_handler,
            idu_catch_p: idu_catch_handler,
            isu_catch_p: isu_catch_handler,
            alu_catch_p: alu_catch_handler,
            lsu_catch_p: lsu_catch_handler,
            wbu_catch: wbu_catch_handler,
            pipeline_catch_p: pipeline_catch_handler,
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

        let jyd_remote_callbacks = JydRemoteCallbacks {
            irom_read: irom_read_handler,
            dramm_read: dram_read_handler,
            dramm_write: dram_write_handler,
        };

        top.init(basic_callbacks, npc_callbacks, ysyxsoc_callbacks, jyd_remote_callbacks);

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

        Self { 
            target: option.cli.platform.simulator,
            top 
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
        let target = match self.target {
            Simulators::NZEA(sim) => Into::<&str>::into(sim),
            _ => unreachable!("WTF")
        };

        NZEA_TIME.with(|time| time.get().unwrap().borrow().print(target))?;
        Ok(())
    }

    fn function_wave_trace(&self, enable: bool) {
        self.top.function_wave_trace(enable);
    }

    fn function_nvboard(&self, enable:bool) {
        self.top.function_nvboard(enable);
    }
}
