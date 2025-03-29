#include "wrapper.h"
#include <memory>

Vtop_container::Vtop_container(const char* scopep__V):
    Vtop(scopep__V) {
    this->m_seqnum = 0;
    this->wave_trace_on = false;

    this->m_trace = new VerilatedFstC;
    Verilated::traceEverOn(true);
    this->trace(m_trace, 99);
    this->m_trace->open("waveform.vcd");
}

void Vtop_container::set_wave_trace_on(bool on) {
    this->wave_trace_on = on;
}

void Vtop_container::wave_trace_once() {
    if (this->wave_trace_on == false) return;

    this->contextp->timeInc(1);
    this->m_trace->dump(contextp->time());
}

void Vtop_container::cycle(uint32_t times) {
    for (uint32_t i = 0; i < times; i++) {
        this->clock = 0; this->eval();
        this->wave_trace_once();

        this->clock = 1; this->eval();
        this->wave_trace_once();
    }
}

void Vtop_container::reset_signal(uint32_t times) {
    this->reset = 1;
    this->cycle(times);
    this->reset = 0;
}

std::unique_ptr<Vtop_container> new_vtop_container_client(const char* scopep__V) {
    return std::make_unique<Vtop_container>(scopep__V);
}
