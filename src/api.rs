use std::os::raw::c_void;

use dlopen2::wrapper::{Container, WrapperApi};

#[derive(Default)]
#[repr(C)]
pub struct Callbacks {
    alu_catch_p: Option<unsafe extern "C" fn()>,
    idu_catch_p: Option<unsafe extern "C" fn(*const u32)>,
    ifu_catch_p: Option<unsafe extern "C" fn(*const u32, *const u32)>,
    icache_mat_catch_p: Option<unsafe extern "C" fn(*const u32)>,
    icache_catch_p: Option<unsafe extern "C" fn(u8, u8)>,
    icache_flush_p: Option<unsafe extern "C" fn()>,
    icache_state_catch_p: Option<unsafe extern "C" fn(*const u32, *const u32, *const u32, *const u32)>,
}

#[derive(WrapperApi)]
struct VTop {
    nzea_protectlib_create: unsafe extern "C" fn() -> *mut c_void,
    nzea_protectlib_final: unsafe extern "C" fn(arg: *mut c_void) -> c_void,

    nzea_protectlib_combo_update: unsafe extern "C" fn(handler: *mut c_void, reset: u8) -> u64,
    nzea_protectlib_seq_update: unsafe extern "C" fn(handler: *mut c_void, clock: u8) -> u64,

    set_callbacks: unsafe extern "C" fn(callbacks: Callbacks),
}

pub struct Top {
    container: Container<VTop>,

    pub model: *mut c_void,
}

impl Top {
    pub fn new() -> Self {
        let container: Container<VTop> =
            unsafe { Container::load("./build/obj_dir/libnzea.so") }.expect("Could not open library or load symbols");

        let model = unsafe { container.nzea_protectlib_create() };
        assert!(!model.is_null(), "Failed to create model");

        Top { 
            container,
            model,
        }
    }

    pub fn init(&self, callbacks: Callbacks) {
        unsafe {
            self.container.set_callbacks(callbacks);
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
