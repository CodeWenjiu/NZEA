#ifndef NZEA_WRAPPER_H
#define NZEA_WRAPPER_H

#include "svdpi.h"
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
    void(*WBU_catch_p)(input, input, input, input, input, input, input, input, input);
} Basic_Callbacks;

typedef struct {
    void(*Uart_putc_p)(input);
    void(*sram_read_p)(input, output);
    void(*sram_write_p)(input, input, input);
} NPC_Callbacks;

static NPC_Callbacks npc_callbacks = {
    .Uart_putc_p = nullptr,
    .sram_read_p = nullptr,
    .sram_write_p = nullptr,
};

static Basic_Callbacks basic_callbacks = {
    .ALU_catch_p = nullptr,
    .IDU_catch_p = nullptr,
    .IFU_catch_p = nullptr,
    .Icache_MAT_catch_p = nullptr,
    .Icache_catch_p = nullptr,
    .Icache_flush_p = nullptr,
    .Icache_state_catch_p = nullptr,
    .LSU_catch_p = nullptr,
    .Pipeline_catch_p = nullptr,
    .WBU_catch_p = nullptr,
};

extern "C" void set_basic_callbacks(Basic_Callbacks cb);

#endif // NZEA_WRAPPER_H