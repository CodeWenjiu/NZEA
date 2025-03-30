#ifndef NZEA_WRAPPER_H
#define NZEA_WRAPPER_H

#include "Vtop__Dpi.h"
#include "Vtop.h"

static void(*ALU_catch_p)() = nullptr;
static void(*IDU_catch_p)(unsigned int) = nullptr;
static void(*IFU_catch_p)(unsigned int, unsigned int) = nullptr;
static void(*Icache_MAT_catch_p)(unsigned int) = nullptr;
static void(*Icache_catch_p)(unsigned int, unsigned int) = nullptr;
static void(*Icache_flush_p)() = nullptr;
static void(*Icache_state_catch_p)(unsigned int, unsigned int, unsigned int, const svBitVecVal*) = nullptr;
static void(*LSU_catch_p)(unsigned int) = nullptr;
static void(*Pipeline_catch_p)() = nullptr;
static void(*Uart_putc_p)(int) = nullptr;
static void(*WBU_catch_p)(unsigned int, unsigned int, unsigned int, unsigned int, unsigned int, unsigned int, unsigned int, unsigned int, unsigned int) = nullptr;
static void(*sram_read_p)(int, int*) = nullptr;
static void(*sram_write_p)(int, int, int) = nullptr;

#endif // NZEA_WRAPPER_H