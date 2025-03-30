#include "wrapper.h"
#include "Vtop__Dpi.h"

#include <iostream>

extern "C" {
    void ALU_catch() {
        std::cout << "ALU_catch" << std::endl;
    }

    void IDU_catch(const svBitVecVal *Inst_Type) {
        std::cout << "IDU_catch" << std::endl;
    }

    void IFU_catch(const svBitVecVal *pc, const svBitVecVal *inst) {
        std::cout << "IFU_catch" << std::endl;
    }

    void Icache_MAT_catch(const svBitVecVal *count) {
        std::cout << "Icache_MAT_catch" << std::endl;
    }

    void Icache_catch(svBit map_hit, svBit cache_hit) {
        std::cout << "Icache_catch" << std::endl;
    }

    void Icache_flush() {
        std::cout << "Icache_flush" << std::endl;
    }

    void Icache_state_catch(const svBitVecVal *write_index, const svBitVecVal *write_way, const svBitVecVal *write_tag, const svBitVecVal *write_data) {
        std::cout << "Icache_state_catch" << std::endl;
    }

    void LSU_catch(unsigned int diff_skip) {
        std::cout << "LSU_catch" << std::endl;
    }

    void Pipeline_catch() {
        std::cout << "Pipeline_catch" << std::endl;
    }

    void Uart_putc(int c) {
        std::cout << "Uart_putc" << std::endl;
    }

    void WBU_catch(unsigned int next_pc, unsigned int gpr_waddr, unsigned int gpr_wdata, unsigned int csr_wena, unsigned int csr_waddra, unsigned int csr_wdataa, unsigned int csr_wenb, unsigned int csr_waddrb, unsigned int csr_wdatab) {
        std::cout << "WBU_catch" << std::endl;
    }

    void sram_read(int addr, int *data) {
        std::cout << "sram_read" << std::endl;
    }

    void sram_write(int addr, int data, int mask) {
        std::cout << "sram_write" << std::endl;
    }
}
