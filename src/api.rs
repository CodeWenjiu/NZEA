use std::{ffi::c_char, os::raw::c_void};

use dlopen2::wrapper::{Container, WrapperApi};
use option_parser::OptionParser;

pub type Input = *const u32;
pub type Output = *mut u32;

#[repr(C)]
pub struct BasicCallbacks {
    pub alu_catch_p: unsafe extern "C" fn(Input),
    pub idu_catch_p: unsafe extern "C" fn(Input, Input),
    pub ifu_catch_p: unsafe extern "C" fn(Input, Input),
    pub icache_mat_catch_p: unsafe extern "C" fn(Input),
    pub icache_catch_p: unsafe extern "C" fn(u8, u8),
    pub icache_flush_p: unsafe extern "C" fn(),
    pub icache_state_catch_p: unsafe extern "C" fn(Input, Input, Input, Input),
    pub lsu_catch_p: unsafe extern "C" fn(Input, u8),
    pub pipeline_catch_p: unsafe extern "C" fn(),
    pub wbu_catch: unsafe extern "C" fn(Input, Input, Input, Input, Input, Input, Input, Input, Input),
}

#[repr(C)]
pub struct NpcCallbacks {
    pub uart_catch: unsafe extern "C" fn(Input),
    pub sram_read: unsafe extern "C" fn(Input, Output),
    pub sram_write: unsafe extern "C" fn(Input, Input, Input),
}

#[derive(WrapperApi)]
struct VTop {
    create_model: unsafe extern "C" fn(scopep_v: *const c_char) -> *mut c_void,
    delete_model: unsafe extern "C" fn(vhandlep_v: *mut c_void) -> c_void,

    reset: unsafe extern "C" fn(vhandlep_v: *mut c_void, time: u64) -> c_void,
    cycle: unsafe extern "C" fn(vhandlep_v: *mut c_void, time: u64) -> u64,

    enable_wave_trace: unsafe extern "C" fn(vhandlep_v: *mut c_void) -> c_void,
    disable_wave_trace: unsafe extern "C" fn(vhandlep_v: *mut c_void) -> c_void,

    enable_nvboard: unsafe extern "C" fn(vhandlep_v: *mut c_void) -> c_void,

    set_basic_callbacks: unsafe extern "C" fn(callbacks: BasicCallbacks),
    set_npc_callbacks: unsafe extern "C" fn(callbacks: NpcCallbacks),
}

pub struct Top {
    container: Container<VTop>,

    pub model: *mut c_void,
}

impl Top {
    pub fn new(option: &OptionParser) -> Self {
        let target = option.cli.platform.simulator;
        let target = match target {
            remu_utils::Simulators::NZEA(sim) => Into::<&str>::into(sim),
            _ => panic!("WTF")
        };

        let so_path = format!("/home/wenjiu/ysyx-workbench/remu/simulator/src/nzea/build/{}/obj_dir/libnzea.so", target);

        let container: Container<VTop> =
            unsafe { Container::load(so_path) }.expect("Could not open library or load symbols");

        let scope = std::ffi::CString::new("0").expect("CString::new failed");
        let model = unsafe { container.create_model(scope.as_ptr()) };
        assert!(!model.is_null(), "Failed to create model");

        Top { 
            container,
            model,
        }
    }

    pub fn init(
        &self, 
        basic_callbacks: BasicCallbacks,
        npc_callbacks: NpcCallbacks,
    ) {
        unsafe {
            self.container.set_basic_callbacks(basic_callbacks);
            self.container.set_npc_callbacks(npc_callbacks);
        }
    }

    pub fn cycle(&self, times: u64) {
        unsafe {
            self.container.cycle(self.model, times);
        }
    }

    pub fn reset(&self, times: u64) {
        unsafe {
            self.container.reset(self.model, times);
        }
    }

    pub fn function_wave_trace(&self, enable: bool) {
        unsafe {
            if enable == true {
                (self.container.enable_wave_trace)(self.model);
            } else {
                (self.container.disable_wave_trace)(self.model);
            }
        }
    }

    pub fn function_nvboard(&self, enable: bool) {
        unsafe {
            if enable == true {
                (self.container.enable_nvboard)(self.model);
            } else {
            }
        }
    }
}

impl Drop for Top {
    fn drop(&mut self) {
        unsafe {
            self.container.delete_model(self.model);
        }
    }
}
