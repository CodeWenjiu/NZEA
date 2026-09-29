// Adjacent write-read pairs where the writer is multi-cycle: `lw t0` then `add t1, t0` right behind it. A load holds
// the execute slot for the bus response, so the consumer arrives in ID/IS while its producer is still resident — the
// one window where the bypass level's reach is what decides the move into IS/EX (at `Execute` the completion's result
// is taken in place; at `Commit` the consumer waits for the write-back register). Straight-line, flush-free.
//
// Assemble (nzea):   just asm-elf dep_chain_load riscv32im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/dep_chain_load-riscv32im.elf \
//                      --isa riscv32im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ func trace wave-form on }' and '{ continue }' \
//                      and '{ stat print }' --difftest remu

    .section .text
    .globl _start
_start:
    li      t0, 0
    li      t1, 0
    li      t2, 0x80000000    // RAM scratch: loads go through the memory path

    .rept 64
    lw      t0, 0(t2)         // write t0 — multi-cycle: the slot is held for the bus response
    add     t1, t0, zero      // read t0: the pair's consumer
    .endr

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass