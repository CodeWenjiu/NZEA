use std::os::raw::c_void;

use dlopen2::wrapper::{Container, WrapperApi};

pub type Input = *const u32;
pub type Output = *mut u32;

#[repr(C)]
pub struct BasicCallbacks {
    pub alu_catch_p: unsafe extern "C" fn(),
    pub idu_catch_p: unsafe extern "C" fn(Input),
    pub ifu_catch_p: unsafe extern "C" fn(Input, Input),
    pub icache_mat_catch_p: unsafe extern "C" fn(Input),
    pub icache_catch_p: unsafe extern "C" fn(u8, u8),
    pub icache_flush_p: unsafe extern "C" fn(),
    pub icache_state_catch_p: unsafe extern "C" fn(Input, Input, Input, Input),
    pub lsu_catch_p: unsafe extern "C" fn(u8),
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
    nzea_protectlib_create: unsafe extern "C" fn() -> *mut c_void,
    nzea_protectlib_final: unsafe extern "C" fn(arg: *mut c_void) -> c_void,

    nzea_protectlib_combo_update: unsafe extern "C" fn(handler: *mut c_void, reset: u8) -> u64,
    nzea_protectlib_seq_update: unsafe extern "C" fn(handler: *mut c_void, clock: u8) -> u64,

    set_basic_callbacks: unsafe extern "C" fn(callbacks: BasicCallbacks),
    set_npc_callbacks: unsafe extern "C" fn(callbacks: NpcCallbacks),
}

pub struct Top {
    container: Container<VTop>,

    pub model: *mut c_void,
}

impl Top {
    pub fn new() -> Self {
        let container: Container<VTop> =
            unsafe { Container::load("./verilater_build/libnzea.so") }.expect("Could not open library or load symbols");

        let model = unsafe { container.nzea_protectlib_create() };
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
            for _ in 0..times {
                self.container.nzea_protectlib_seq_update(self.model, 1);
                self.container.nzea_protectlib_seq_update(self.model, 0);
            }
        }
    }

    pub fn reset(&self, times: u64) {
        unsafe {
            self.container.nzea_protectlib_combo_update(self.model, 1);
            self.cycle(times);
            self.container.nzea_protectlib_combo_update(self.model, 0);
        }
    }
}

impl Drop for Top {
    fn drop(&mut self) {
        unsafe {
            self.container.nzea_protectlib_final(self.model);
        }
    }
}
