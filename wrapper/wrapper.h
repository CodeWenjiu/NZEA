#ifndef WRAPPER_H
#define WRAPPER_H

#include "verilated.h"
#include "verilated_fst_c.h"

#include "Vtop.h"
// #include "verilated_dpi.h"
#include <cstdint>

class Vtop_container: public Vtop {
    private:
        uint64_t m_seqnum;
        VerilatedFstC* m_trace;
        const std::unique_ptr<VerilatedContext> contextp{new VerilatedContext};

        bool wave_trace_on;
    public:
        Vtop_container(const char* scopep__V);
        
        void set_wave_trace_on(bool on);

        void wave_trace_once();

        void cycle(uint32_t times);

        void reset_signal(uint32_t times);
};

std::unique_ptr<Vtop_container> new_vtop_container_client(const char* scopep__V);

#endif
