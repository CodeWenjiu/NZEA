remu_macro::mod_flat!(src);

#[test]
fn test() {
    use remu_macro::log_debug;
    use logger::Logger;
    let top = Top::new();

    unsafe extern "C" fn alu_catch_handler(_pc: Input) {
        log_debug!("alu_catch_p");
    }

    unsafe extern "C" fn idu_catch_handler(_pc: Input, _inst_type: Input) {
        log_debug!("idu_catch_p");
    }

    unsafe extern "C" fn ifu_catch_handler(_pc: Input, _inst: Input) {
        log_debug!("ifu_catch_p");
    }

    unsafe extern "C" fn icache_mat_catch_handler(_count: Input) {
        log_debug!("icache_mat_catch_p");
    }

    unsafe extern "C" fn icache_catch_handler(_map_hit: u8, _cache_hit: u8) {
        log_debug!("icache_catch_p");
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
        log_debug!("icache_state_catch_p");
    }

    unsafe extern "C" fn lsu_catch_handler(_pc: Input, _diff_skip: u8) {
        log_debug!("lsu_catch_p");
    }

    unsafe extern "C" fn pipeline_catch_handler() {
        log_debug!("pipeline_catch_p");
    }

    unsafe extern "C" fn uart_catch_handler(_c: Input) {
        log_debug!("uart_catch");
    }

    unsafe extern "C" fn wbu_catch_handler(
        _next_pc: Input,
        _gpr_waddr: Input,
        _gpr_wdata: Input,
        _csr_wena: Input,
        _csr_waddra: Input,
        _csr_wdataa: Input,
        _csr_wenb: Input,
        _csr_waddrb: Input,
        _csr_wdatab: Input,
    ) {
        log_debug!("wbu_catch");
    }

    unsafe extern "C" fn sram_read_handler(_addr: Input, _data: Output) {
        log_debug!("sram_read");
    }

    unsafe extern "C" fn sram_write_handler(_addr: Input, _data: Input, _mask: Input) {
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
