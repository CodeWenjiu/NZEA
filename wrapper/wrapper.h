#ifndef NZEA_WRAPPER_H
#define NZEA_WRAPPER_H

#include "svdpi.h"
typedef const svBitVecVal* input;
typedef svBitVecVal* output;
typedef svBit bits;

typedef struct {
    void(*BPU_catch_p)(input);

    void(*btb_cache_meta_write_p)(char set, char way, int tag);
    void(*btb_cache_data_write_p)(char set, char way, char block, int data);
    void(*icache_cache_meta_write_p)(char set, char way, int tag);
    void(*icache_cache_data_write_p)(char set, char way, char block, int data);
    
    void(*IFU_catch_p)(input, input);
    void(*IDU_catch_p)(input);
    void(*ISU_catch_p)(input, bits);
    void(*ALU_catch_p)(input);
    void(*LSU_catch_p)(input, bits, input);
    void(*WBU_catch_p)(input, input, input, bits, input, input);
    void(*Pipeline_catch_p)();
} Basic_Callbacks;

typedef struct {
    void(*Uart_putc_p)(input);
    void(*sram_read_p)(input, output);
    void(*sram_write_p)(input, input, input);
} NPC_Callbacks;

typedef struct {
    void(*flash_read)(int32_t, int32_t*);
    void(*mrom_read)(int32_t, int32_t*);
    void(*psram_write)(int32_t, int32_t, int32_t);
    void(*psram_read)(int32_t, int32_t*);
    void(*sdram_write)(int32_t, int32_t, int32_t);
    void(*sdram_read)(int32_t, int32_t*);
    void(*vga_write)(int32_t, int32_t);
    void(*vga_read)(int32_t, int32_t, int32_t*);
} YSYXSOC_Callbacks;

typedef struct {
    void(*IROM_read)(input, output);
    void(*DRAM_read)(input, input, output);
    void(*DRAM_write)(input, input, input);
} JYD_REMOTE_Callbacks;

#include <stdio.h>
#include <stdlib.h>

// Macro to define default panic functions
#define DEFINE_PANIC_FUNCTION(name, ...) \
    static void default_##name(__VA_ARGS__) { \
        fprintf(stderr, "Error: " #name " called but not initialized\n"); \
        exit(1); \
    }

// Define panic functions for Basic_Callbacks
DEFINE_PANIC_FUNCTION(BPU_catch_p, input a)

DEFINE_PANIC_FUNCTION(btb_cache_meta_write_p, char set, char way, int tag)
DEFINE_PANIC_FUNCTION(btb_cache_data_write_p, char set, char way, char block, int data)
DEFINE_PANIC_FUNCTION(icache_cache_meta_write_p, char set, char way, int tag)
DEFINE_PANIC_FUNCTION(icache_cache_data_write_p, char set, char way, char block, int data)

DEFINE_PANIC_FUNCTION(ALU_catch_p, input a)
DEFINE_PANIC_FUNCTION(IDU_catch_p, input a)
DEFINE_PANIC_FUNCTION(IFU_catch_p, input a, input b)
DEFINE_PANIC_FUNCTION(ISU_catch_p, input a, bits b)
DEFINE_PANIC_FUNCTION(LSU_catch_p, input a, bits b, input c)
DEFINE_PANIC_FUNCTION(Pipeline_catch_p, void)
DEFINE_PANIC_FUNCTION(WBU_catch_p, input a, input b, input c, bits d, input e, input f)

// Define panic functions for NPC_Callbacks
DEFINE_PANIC_FUNCTION(Uart_putc_p, input a)
DEFINE_PANIC_FUNCTION(sram_read_p, input a, output b)
DEFINE_PANIC_FUNCTION(sram_write_p, input a, input b, input c)

// Define panic functions for YSYXSOC_Callbacks
DEFINE_PANIC_FUNCTION(flash_read, int32_t a, int32_t* b)
DEFINE_PANIC_FUNCTION(mrom_read, int32_t a, int32_t* b)
DEFINE_PANIC_FUNCTION(psram_write, int32_t a, int32_t b, int32_t c)
DEFINE_PANIC_FUNCTION(psram_read, int32_t a, int32_t* b)
DEFINE_PANIC_FUNCTION(sdram_write, int32_t a, int32_t b, int32_t c)
DEFINE_PANIC_FUNCTION(sdram_read, int32_t a, int32_t* b)
DEFINE_PANIC_FUNCTION(vga_write, int32_t a, int32_t b)
DEFINE_PANIC_FUNCTION(vga_read, int32_t a, int32_t b, int32_t* c)

// Define panic functions for JYD_REMOTE_Callbacks
DEFINE_PANIC_FUNCTION(IROM_read, input addr, output data)
DEFINE_PANIC_FUNCTION(DRAM_read, input addr, input mask, output data)
DEFINE_PANIC_FUNCTION(DRAM_write, input addr, input mask, input data)

static Basic_Callbacks basic_callbacks = {
    .BPU_catch_p = default_BPU_catch_p,

    .btb_cache_meta_write_p = default_btb_cache_meta_write_p,
    .btb_cache_data_write_p = default_btb_cache_data_write_p,
    .icache_cache_meta_write_p = default_icache_cache_meta_write_p,
    .icache_cache_data_write_p = default_icache_cache_data_write_p,

    .IFU_catch_p = default_IFU_catch_p,
    .IDU_catch_p = default_IDU_catch_p,
    .ISU_catch_p = default_ISU_catch_p,
    .ALU_catch_p = default_ALU_catch_p,
    .LSU_catch_p = default_LSU_catch_p,
    .WBU_catch_p = default_WBU_catch_p,
    .Pipeline_catch_p = default_Pipeline_catch_p,
};

static NPC_Callbacks npc_callbacks = {
    .Uart_putc_p = default_Uart_putc_p,
    .sram_read_p = default_sram_read_p,
    .sram_write_p = default_sram_write_p,
};

static YSYXSOC_Callbacks ysyxsoc_callbacks = {
    .flash_read = default_flash_read,
    .mrom_read = default_mrom_read,
    .psram_write = default_psram_write,
    .psram_read = default_psram_read,
    .sdram_write = default_sdram_write,
    .sdram_read = default_sdram_read,
    .vga_write = default_vga_write,
    .vga_read = default_vga_read,
};

static JYD_REMOTE_Callbacks jyd_remote_callbacks = {
    .IROM_read = default_IROM_read,
    .DRAM_read = default_DRAM_read,
    .DRAM_write = default_DRAM_write,
};

#endif // NZEA_WRAPPER_H