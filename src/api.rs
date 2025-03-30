use std::os::raw::c_void;

use dlopen2::wrapper::{Container, WrapperApi};

#[derive(WrapperApi)]
struct VTop {
    nzea_protectlib_create: unsafe extern "C" fn() -> *mut c_void,
    nzea_protectlib_final: unsafe extern "C" fn(arg: *mut c_void) -> c_void,

    nzea_protectlib_combo_update: unsafe extern "C" fn(handler: *mut c_void, reset: u8) -> u64,
    nzea_protectlib_seq_update: unsafe extern "C" fn(handler: *mut c_void, clock: u8) -> u64,
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
