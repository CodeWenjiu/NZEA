#ifndef NZEA_WRAPPER_H
#define NZEA_WRAPPER_H

#include "Vtop__Dpi.h"
#include "Vtop.h"

typedef const svBitVecVal* input;
typedef svBitVecVal* output;
typedef svBit bits;

typedef struct {
    void(*ALU_catch_p)();
    void(*IDU_catch_p)(input);
    void(*IFU_catch_p)(input, input);
    void(*Icache_MAT_catch_p)(input);
    void(*Icache_catch_p)(bits, bits);
    void(*Icache_flush_p)();
    void(*Icache_state_catch_p)(input, input, input, input);
    void(*LSU_catch_p)(bits);
    void(*Pipeline_catch_p)();
    void(*Uart_putc_p)(input);
    void(*WBU_catch_p)(input, input, input, input, input, input, input, input, input);
    void(*sram_read_p)(input, output);
    void(*sram_write_p)(input, input, input);
} Basic_Callbacks;

static Basic_Callbacks callbacks = {
    .ALU_catch_p = NULL,
    .IDU_catch_p = NULL,
    .IFU_catch_p = NULL,
    .Icache_MAT_catch_p = NULL,
    .Icache_catch_p = NULL,
    .Icache_flush_p = NULL,
    .Icache_state_catch_p = NULL,
    .LSU_catch_p = NULL,
    .Pipeline_catch_p = NULL,
    .Uart_putc_p = NULL,
    .WBU_catch_p = NULL,
    .sram_read_p = NULL,
    .sram_write_p = NULL
};

extern "C" void set_basic_callbacks(Basic_Callbacks cb);

#endif // NZEA_WRAPPER_H