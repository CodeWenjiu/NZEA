remu_macro::mod_flat!(api);

#[test]
fn test() {
    use remu_macro::log_debug;
    use logger::Logger;
    let top = Top::new();

    unsafe extern "C" fn alu_catch_handler() {
        log_debug!("alu_catch_p");
    }

    unsafe extern "C" fn idu_catch_handler(_arg: *const u32) {
        log_debug!("idu_catch_p");
    }

    unsafe extern "C" fn ifu_catch_handler(_arg1: *const u32, _arg2: *const u32) {
        log_debug!("ifu_catch_p");
    }

    unsafe extern "C" fn icache_mat_catch_handler(_arg: *const u32) {
        log_debug!("icache_mat_catch_p");
    }

    unsafe extern "C" fn icache_catch_handler(_arg1: u8, _arg2: u8) {
        log_debug!("icache_catch_p");
    }

    unsafe extern "C" fn icache_flush_handler() {
        log_debug!("icache_flush_p");
    }

    unsafe extern "C" fn icache_state_catch_handler(
        _arg1: *const u32,
        _arg2: *const u32,
        _arg3: *const u32,
        _arg4: *const u32,
    ) {
        log_debug!("icache_state_catch_p");
    }

    unsafe extern "C" fn lsu_catch_handler(_arg: u8) {
        log_debug!("lsu_catch_p");
    }

    unsafe extern "C" fn pipeline_catch_handler() {
        log_debug!("pipeline_catch_p");
    }

    unsafe extern "C" fn uart_catch_handler(_arg: *const u32) {
        log_debug!("uart_catch");
    }

    unsafe extern "C" fn wbu_catch_handler(
        _arg1: *const u32,
        _arg2: *const u32,
        _arg3: *const u32,
        _arg4: *const u32,
        _arg5: *const u32,
        _arg6: *const u32,
        _arg7: *const u32,
        _arg8: *const u32,
        _arg9: *const u32,
    ) {
        log_debug!("wbu_catch");
    }

    unsafe extern "C" fn sram_read_handler(_arg1: *const u32, _arg2: *mut u32) {
        log_debug!("sram_read");
    }

    unsafe extern "C" fn sram_write_handler(_arg1: *const u32, _arg2: *const u32, _arg3: *const u32) {
        log_debug!("sram_write");
    }

    let callbacks = BasicCallbacks {
        alu_catch_p: alu_catch_handler,
        idu_catch_p: idu_catch_handler,
        ifu_catch_p: ifu_catch_handler,
        icache_mat_catch_p: icache_mat_catch_handler,
        icache_catch_p: icache_catch_handler,
        icache_flush_p: icache_flush_handler,
        icache_state_catch_p: icache_state_catch_handler,
        lsu_catch_p: lsu_catch_handler,
        pipeline_catch_p: pipeline_catch_handler,
        uart_catch: uart_catch_handler,
        wbu_catch: wbu_catch_handler,
        sram_read: sram_read_handler,
        sram_write: sram_write_handler,
    };
    
    top.init(callbacks);

    top.reset(10);

    top.cycle(10);
}
