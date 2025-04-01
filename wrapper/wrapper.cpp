#include "wrapper.h"
#include <iostream>

extern "C" {
    // basic callbacks

    void set_basic_callbacks(Basic_Callbacks cb) {
        basic_callbacks = cb;
    }

    void ALU_catch() {
        basic_callbacks.ALU_catch_p();
    }

    void IDU_catch(const svBitVecVal *Inst_Type) {
        basic_callbacks.IDU_catch_p(Inst_Type);
    }

    void IFU_catch(const svBitVecVal *pc, const svBitVecVal *inst) {
        basic_callbacks.IFU_catch_p(pc, inst);
    }

    void Icache_MAT_catch(const svBitVecVal *count) {
        basic_callbacks.Icache_MAT_catch_p(count);
    }

    void Icache_catch(svBit map_hit, svBit cache_hit) {
        basic_callbacks.Icache_catch_p(map_hit, cache_hit);
    }

    void Icache_flush() {
        basic_callbacks.Icache_flush_p();
    }

    void Icache_state_catch(const svBitVecVal *write_index, const svBitVecVal *write_way, const svBitVecVal *write_tag, const svBitVecVal *write_data) {
        basic_callbacks.Icache_state_catch_p(write_index, write_way, write_tag, write_data);
    }

    void LSU_catch(svBit diff_skip) {
        basic_callbacks.LSU_catch_p(diff_skip);
    }

    void Pipeline_catch() {
        basic_callbacks.Pipeline_catch_p();
    }

    void WBU_catch(const svBitVecVal *next_pc, const svBitVecVal *gpr_waddr, const svBitVecVal *gpr_wdata, const svBitVecVal *csr_wena, const svBitVecVal *csr_waddra, const svBitVecVal *csr_wdataa, const svBitVecVal *csr_wenb, const svBitVecVal *csr_waddrb, const svBitVecVal *csr_wdatab) {
        basic_callbacks.WBU_catch_p(next_pc, gpr_waddr, gpr_wdata, csr_wena, csr_waddra, csr_wdataa, csr_wenb, csr_waddrb, csr_wdatab);
    }

    // npc callbacks

    void set_npc_callbacks(NPC_Callbacks cb) {
        npc_callbacks = cb;
    }

    void Uart_putc(const svBitVecVal *c) {
        npc_callbacks.Uart_putc_p(c);
    }

    void sram_read(const svBitVecVal *addr, svBitVecVal *data) {
        npc_callbacks.sram_read_p(addr, data);
    }

    void sram_write(const svBitVecVal *addr, const svBitVecVal *data, const svBitVecVal *mask) {
        npc_callbacks.sram_write_p(addr, data, mask);
    }
}
