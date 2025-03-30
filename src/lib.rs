remu_macro::mod_flat!(api);

#[test]
fn test() {
    use remu_macro::log_debug;
    use logger::Logger;
    let top = Top::new();

    unsafe extern "C" fn alu_catch_handler() {
        log_debug!("alu_catch_p");
    }

    unsafe extern "C" fn idu_catch_handler(_inst_type: *const u32) {
        log_debug!("idu_catch_p");
    }

    unsafe extern "C" fn ifu_catch_handler(_pc: *const u32, _inst: *const u32) {
        log_debug!("ifu_catch_p");
    }

    unsafe extern "C" fn icache_mat_catch_handler(_count: *const u32) {
        log_debug!("icache_mat_catch_p");
    }

    unsafe extern "C" fn icache_catch_handler(_map_hit: u8, _cache_hit: u8) {
        log_debug!("icache_catch_p");
    }

    unsafe extern "C" fn icache_flush_handler() {
        log_debug!("icache_flush_p");
    }

    unsafe extern "C" fn icache_state_catch_handler(
        _write_index: *const u32,
        _write_way: *const u32,
        _write_tag: *const u32,
        _write_data: *const u32,
    ) {
        log_debug!("icache_state_catch_p");
    }

    unsafe extern "C" fn lsu_catch_handler(_diff_skip: u8) {
        log_debug!("lsu_catch_p");
    }

    unsafe extern "C" fn pipeline_catch_handler() {
        log_debug!("pipeline_catch_p");
    }

    unsafe extern "C" fn uart_catch_handler(_c: *const u32) {
        log_debug!("uart_catch");
    }

    unsafe extern "C" fn wbu_catch_handler(
        _next_pc: *const u32,
        _gpr_waddr: *const u32,
        _gpr_wdata: *const u32,
        _csr_wena: *const u32,
        _csr_waddra: *const u32,
        _csr_wdataa: *const u32,
        _csr_wenb: *const u32,
        _csr_waddrb: *const u32,
        _csr_wdatab: *const u32,
    ) {
        log_debug!("wbu_catch");
    }

    unsafe extern "C" fn sram_read_handler(_addr: *const u32, _data: *mut u32) {
        log_debug!("sram_read");
    }

    unsafe extern "C" fn sram_write_handler(_addr: *const u32, _data: *const u32, _mask: *const u32) {
        log_debug!("sram_write");
    }

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

    top.reset(10);

    top.cycle(10);
}
