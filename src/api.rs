use std::{env, ffi::c_char, os::raw::c_void, process::Command};

use dlopen2::wrapper::{Container, WrapperApi};
use option_parser::OptionParser;
use remu_macro::log_info;

pub type Input = *const u32;
pub type Output = *mut u32;

#[repr(C)]
pub struct BasicCallbacks {
    pub ifu_catch_p: unsafe extern "C" fn(Input, Input),
    pub icache_mat_catch_p: unsafe extern "C" fn(Input),
    pub icache_catch_p: unsafe extern "C" fn(u8, u8),
    pub icache_flush_p: unsafe extern "C" fn(),
    pub icache_state_catch_p: unsafe extern "C" fn(Input, Input, Input, Input),
    pub idu_catch_p: unsafe extern "C" fn(Input),
    pub isu_catch_p: unsafe extern "C" fn(Input, u8),
    pub alu_catch_p: unsafe extern "C" fn(Input),
    pub lsu_catch_p: unsafe extern "C" fn(Input, u8, Input),
    pub wbu_catch: unsafe extern "C" fn(Input, Input, Input, Input, Input, Input, Input, Input, Input),
    pub pipeline_catch_p: unsafe extern "C" fn(),
}

#[repr(C)]
pub struct NpcCallbacks {
    pub uart_catch: unsafe extern "C" fn(Input),
    pub sram_read: unsafe extern "C" fn(Input, Output),
    pub sram_write: unsafe extern "C" fn(Input, Input, Input),
}

#[repr(C)]
pub struct YsyxsocCallbacks {
    pub flash_read: unsafe extern "C" fn(u32, *mut u32),
    pub mrom_read: unsafe extern "C" fn(u32, *mut u32),
    pub psram_write: unsafe extern "C" fn(u32, u32, u32),
    pub psram_read: unsafe extern "C" fn(u32, *mut u32),
    pub sdram_write: unsafe extern "C" fn(u32, u32, u32),
    pub sdram_read: unsafe extern "C" fn(u32, *mut u32),
    pub vga_write: unsafe extern "C" fn(u32, u32),
    pub vga_read: unsafe extern "C" fn(u32, u32, *mut u32),
}

#[repr(C)]
pub struct JydRemoteCallbacks {
    pub irom_read: unsafe extern "C" fn(Input, Output),
    pub dramm_read: unsafe extern "C" fn(Input, Input, Output),
    pub dramm_write: unsafe extern "C" fn(Input, Input, Input),
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
    set_ysyxsoc_callbacks: unsafe extern "C" fn(callbacks: YsyxsocCallbacks),
    set_jyd_remote_callbacks: unsafe extern "C" fn(callbacks: JydRemoteCallbacks),
}

pub struct Top {
    container: Container<VTop>,

    pub model: *mut c_void,
}

impl Top {
    pub fn new(option: &OptionParser) -> Self {
        let target = option.cli.platform.simulator;
        use remu_utils::Simulators::NZEA;
        let target = match target {
            NZEA(sim) => Into::<&str>::into(sim),
            _ => panic!("WTF")
        };

        let exec_dir = env::current_dir().unwrap();
        let project_root = exec_dir
            .ancestors()
            .find(|p| p.join("simulator").exists())
            .unwrap();

        let target_lower = target.to_string().to_lowercase();

        let nzea_root = project_root
            .join("simulator/src/nzea");

        log_info!("Building NZEA");

        let output = Command::new("sh")
            .arg("-c")
            .arg(format!("make -C {} PLATFORM={}", nzea_root.display(), target_lower))
            .output()
            .expect("Failed to execute build command");

        if !output.status.success() {
            eprintln!("stderr: {}", String::from_utf8_lossy(&output.stderr));
            eprintln!("stdout: {}", String::from_utf8_lossy(&output.stdout));
            panic!("Failed to build NZEA");
        }

        let so_path = nzea_root
            .join("build")
            .join(target_lower)
            .join("obj_dir/libnzea.so");

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
        ysyxsoc_callbacks: YsyxsocCallbacks,
        jyd_remote_callbacks: JydRemoteCallbacks,
    ) {
        unsafe {
            self.container.set_basic_callbacks(basic_callbacks);
            self.container.set_npc_callbacks(npc_callbacks);
            self.container.set_ysyxsoc_callbacks(ysyxsoc_callbacks);
            self.container.set_jyd_remote_callbacks(jyd_remote_callbacks);
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
