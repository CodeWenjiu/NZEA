#include "wrapper.h"
#include "verilated_fst_c.h"
#include "Vtop.h"
#include <nvboard.h>
#include <iostream>

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
                if (FILE *file = fopen("waveform.fst", "r")) {
                    fclose(file);
                    remove("waveform.fst");
                }
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

        void dump_wave() {
            if (this->tfp && this->wave_trace_on) {
                this->tfp->dump(this->contextp->time());
                this->contextp->timeInc(1);
            }
        }

        void cycle() {
            this->clock = 0;
            this->eval();

            this->dump_wave();

            this->clock = 1;
            this->eval();

            this->dump_wave();

            if (this->nvboard_on) {
                nvboard_update();
            }
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

    void IDU_catch(const svBitVecVal *pc) {
        basic_callbacks.IDU_catch_p(pc);
    }

    void IFU_catch(const svBitVecVal *pc, const svBitVecVal *inst) {
        basic_callbacks.IFU_catch_p(pc, inst);
    }

    void BPU_catch(const svBitVecVal *pc) {
        basic_callbacks.BPU_catch_p(pc);
    }

    void btb_cache_meta_write(char set, char way, int tag) {
        basic_callbacks.btb_cache_meta_write_p(set, way, tag);
    }

    void btb_cache_data_write(char set, char way, char block, int data) {
        basic_callbacks.btb_cache_data_write_p(set, way, block, data);
    }

    void icache_cache_meta_write(char set, char way, int tag) {
        basic_callbacks.icache_cache_meta_write_p(set, way, tag);
    }

    void icache_cache_data_write(char set, char way, char block, int data) {
        basic_callbacks.icache_cache_data_write_p(set, way, block, data);
    }

    void ISU_catch(const svBitVecVal *pc, svBit inst_type) {
        basic_callbacks.ISU_catch_p(pc, inst_type);
    }

    void LSU_catch(const svBitVecVal *pc, svBit diff_skip, const svBitVecVal *skip_val) {
        basic_callbacks.LSU_catch_p(pc, diff_skip, skip_val);
    }

    void Pipeline_catch() {
        basic_callbacks.Pipeline_catch_p();
    }

    void WBU_catch(const svBitVecVal *next_pc, const svBitVecVal *gpr_waddr, const svBitVecVal *gpr_wdata, svBit csr_wen, const svBitVecVal *csr_waddr, const svBitVecVal *csr_wdata) {
        basic_callbacks.WBU_catch_p(next_pc, gpr_waddr, gpr_wdata, csr_wen, csr_waddr, csr_wdata);
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

    // ysyxsoc callback

    void set_ysyxsoc_callbacks(YSYXSOC_Callbacks cb) {
        ysyxsoc_callbacks = cb;
    }

    void flash_read(int32_t addr, int32_t* data) {
        ysyxsoc_callbacks.flash_read(addr, data);
    }

    void mrom_read(int32_t addr, int32_t* data) {
        ysyxsoc_callbacks.mrom_read(addr, data);
    }

    void psram_write(int32_t waddr, int32_t wdata, int32_t wlen){
        ysyxsoc_callbacks.psram_write(waddr, wdata, wlen);
    }

    void psram_read(int32_t addr, int32_t* data) {
        ysyxsoc_callbacks.psram_read(addr, data);
    }

    void sdram_write(int32_t waddr, int32_t wdata, int32_t wlen) {
        ysyxsoc_callbacks.sdram_write(waddr, wdata, wlen);
    }

    void sdram_read(int32_t addr, int32_t* data) {
        ysyxsoc_callbacks.sdram_read(addr, data);
    }
    
    void vga_write(int32_t waddr, int32_t wdata) {
        ysyxsoc_callbacks.vga_write(waddr, wdata);
    }
    
    void vga_read(int32_t x_addr, int32_t y_addr, int32_t* rdata) {
        ysyxsoc_callbacks.vga_read(x_addr, y_addr, rdata);
    }

    // jyd remote callback

    void set_jyd_remote_callbacks(JYD_REMOTE_Callbacks cb) {
        jyd_remote_callbacks = cb;
    }

    void IROM_read(const svBitVecVal *addr, svBitVecVal *data) {
        jyd_remote_callbacks.IROM_read(addr, data);
    }

    void DRAM_read(const svBitVecVal *addr, const svBitVecVal *mask, svBitVecVal *data) {
        jyd_remote_callbacks.DRAM_read(addr, mask, data);
    }

    void DRAM_write(const svBitVecVal *addr, const svBitVecVal *mask, const svBitVecVal *data) {
        jyd_remote_callbacks.DRAM_write(addr, mask, data);
    }
}
