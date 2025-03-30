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
} Callbacks;

static Callbacks callbacks = {
    .ALU_catch_p = NULL,
    .IDU_catch_p = NULL,
    .IFU_catch_p = NULL,
    .Icache_MAT_catch_p = NULL,
    .Icache_catch_p = NULL,
    .Icache_flush_p = NULL,
    .Icache_state_catch_p = NULL,
};

extern "C" void set_callbacks(Callbacks cb);

#endif // NZEA_WRAPPER_H