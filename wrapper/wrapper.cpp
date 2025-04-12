#include "wrapper.h"
#include "verilated_fst_c.h"
#include "Vtop.h"
#include <nvboard.h>

class VTop_container: public Vtop {
    public:
        const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};
        VTop_container(const char* scopep__V):
        Vtop(scopep__V) {
        }

        VerilatedFstC* tfp = nullptr;
        bool wave_trace_on;
        void enable_wave_trace() {
            if (!tfp) {
                tfp = new VerilatedFstC;
                this->trace(tfp, 99);
                tfp->open("waveform.fst");
            }
            this->wave_trace_on = true;
        }

        void disable_wave_trace() {
            this->wave_trace_on = false;
        }

        bool nvboard_on = false;
        void enable_nvboard() {
            void nvboard_bind_all_pins(Vtop* top);
            if (!nvboard_on) {
                nvboard_bind_all_pins(this);
                nvboard_init();
                nvboard_on = true;
            }
        }

        void cycle() {
            this->clock = 0;
            this->eval();

            if (this->tfp && this->wave_trace_on) {
                this->tfp->dump(this->contextp->time());
            }

            this->contextp->timeInc(1);

            this->clock = 1;
            this->eval();

            if (this->tfp && this->wave_trace_on) {
                this->tfp->dump(this->contextp->time());
            }

            if (this->nvboard_on) {
                nvboard_update();
            }

            this->contextp->timeInc(1);
        }

        uint64_t seq_tick(uint64_t time) {
            for (uint64_t i = 0; i < time; i++) {
                this->cycle();
            }
            return this->contextp->time();
        }

        void com_tick(uint64_t time) {
            this->reset = 1;
            this->seq_tick(time);
            this->reset = 0;
        }

        void final_container() {
            if (tfp) {
                tfp->close();
                delete tfp;
                tfp = nullptr;
            }
            if (nvboard_on) {
                nvboard_quit();
            }
            this->final();
        }
};

extern "C" {
    // debug functions
    void* create_model(const char* scopep__V) {
        Verilated::traceEverOn(true);
        VTop_container* const handlep__V = new VTop_container{scopep__V};
        return handlep__V;
    }

    uint64_t cycle(void* vhandlep__V, uint64_t time) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        return handlep__V->seq_tick(time);
    }

    void reset(void* vhandlep__V, uint64_t time) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        handlep__V->com_tick(time);
    }

    void enable_wave_trace(void* vhandlep__V) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        handlep__V->enable_wave_trace();
    }

    void disable_wave_trace(void* vhandlep__V) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        handlep__V->disable_wave_trace();
    }

    void enable_nvboard(void* vhandlep__V) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        handlep__V->enable_nvboard();
    }

    void delete_model(void* vhandlep__V) {
        VTop_container* const handlep__V = static_cast<VTop_container*>(vhandlep__V);
        handlep__V->final_container();
    }

    // basic cpu callbacks

    void set_basic_callbacks(Basic_Callbacks cb) {
        basic_callbacks = cb;
    }

    void ALU_catch(const svBitVecVal *pc) {
        basic_callbacks.ALU_catch_p(pc);
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
