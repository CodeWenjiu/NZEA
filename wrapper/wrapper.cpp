#include "wrapper.h"
#include <iostream>

extern "C" {
    void ALU_catch() {
        std::cout << "ALU_catch called" << std::endl;
        callbacks.ALU_catch_p();
    }

    void IDU_catch(const svBitVecVal *Inst_Type) {
        std::cout << "IDU_catch called" << std::endl;
        callbacks.IDU_catch_p(Inst_Type);
    }

    void IFU_catch(const svBitVecVal *pc, const svBitVecVal *inst) {
        std::cout << "IFU_catch called" << std::endl;
        callbacks.IFU_catch_p(pc, inst);
    }

    void Icache_MAT_catch(const svBitVecVal *count) {
        std::cout << "Icache_MAT_catch called" << std::endl;
        callbacks.Icache_MAT_catch_p(count);
    }

    void Icache_catch(svBit map_hit, svBit cache_hit) {
        std::cout << "Icache_catch called" << std::endl;
        callbacks.Icache_catch_p(map_hit, cache_hit);
    }

    void Icache_flush() {
        std::cout << "Icache_flush called" << std::endl;
        callbacks.Icache_flush_p();
    }

    void Icache_state_catch(const svBitVecVal *write_index, const svBitVecVal *write_way, const svBitVecVal *write_tag, const svBitVecVal *write_data) {
        std::cout << "Icache_state_catch called" << std::endl;
        callbacks.Icache_state_catch_p(write_index, write_way, write_tag, write_data);
    }

    void set_callbacks(Callbacks cb) {
        std::cout << "set_callbacks called" << std::endl;
        callbacks = cb;
    }
}
