#include "wrapper.h"
#include <iostream>

extern "C" {
    void ALU_catch() {
        std::cout << "ALU_catch called" << std::endl;
        basic_callbacks.ALU_catch_p();
    }

    void IDU_catch(const svBitVecVal *Inst_Type) {
        std::cout << "IDU_catch called" << std::endl;
        basic_callbacks.IDU_catch_p(Inst_Type);
    }

    void IFU_catch(const svBitVecVal *pc, const svBitVecVal *inst) {
        std::cout << "IFU_catch called" << std::endl;
        basic_callbacks.IFU_catch_p(pc, inst);
    }

    void Icache_MAT_catch(const svBitVecVal *count) {
        std::cout << "Icache_MAT_catch called" << std::endl;
        basic_callbacks.Icache_MAT_catch_p(count);
    }

    void Icache_catch(svBit map_hit, svBit cache_hit) {
        std::cout << "Icache_catch called" << std::endl;
        basic_callbacks.Icache_catch_p(map_hit, cache_hit);
    }

    void Icache_flush() {
        std::cout << "Icache_flush called" << std::endl;
        basic_callbacks.Icache_flush_p();
    }

    void Icache_state_catch(const svBitVecVal *write_index, const svBitVecVal *write_way, const svBitVecVal *write_tag, const svBitVecVal *write_data) {
        std::cout << "Icache_state_catch called" << std::endl;
        basic_callbacks.Icache_state_catch_p(write_index, write_way, write_tag, write_data);
    }

    void LSU_catch(svBit diff_skip) {
        std::cout << "LSU_catch called" << std::endl;
        basic_callbacks.LSU_catch_p(diff_skip);
    }

    void Pipeline_catch() {
        std::cout << "Pipeline_catch called" << std::endl;
        basic_callbacks.Pipeline_catch_p();
    }

    void Uart_putc(const svBitVecVal *c) {
        std::cout << "Uart_putc called" << std::endl;
        npc_callbacks.Uart_putc_p(c);
    }

    void WBU_catch(const svBitVecVal *next_pc, const svBitVecVal *gpr_waddr, const svBitVecVal *gpr_wdata, const svBitVecVal *csr_wena, const svBitVecVal *csr_waddra, const svBitVecVal *csr_wdataa, const svBitVecVal *csr_wenb, const svBitVecVal *csr_waddrb, const svBitVecVal *csr_wdatab) {
        std::cout << "WBU_catch called" << std::endl;
        basic_callbacks.WBU_catch_p(next_pc, gpr_waddr, gpr_wdata, csr_wena, csr_waddra, csr_wdataa, csr_wenb, csr_waddrb, csr_wdatab);
    }

    void sram_read(const svBitVecVal *addr, svBitVecVal *data) {
        std::cout << "sram_read called" << std::endl;
        npc_callbacks.sram_read_p(addr, data);
    }

    void sram_write(const svBitVecVal *addr, const svBitVecVal *data, const svBitVecVal *mask) {
        std::cout << "sram_write called" << std::endl;
        npc_callbacks.sram_write_p(addr, data, mask);
    }

    void set_basic_callbacks(Basic_Callbacks cb) {
        basic_callbacks = cb;
    }

    void set_npc_callbacks(NPC_Callbacks cb) {
        npc_callbacks = cb;
    }
}
