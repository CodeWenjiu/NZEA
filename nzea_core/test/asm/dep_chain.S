// Adjacent write-read pairs, straight-line: `addi t0` then `add t1, t0` right behind it — each pair is a RAW one
// instruction apart, and the traced region has no branch (the finisher's tail runs only after everything retired).
// Deterministic: the same RTL shape must retire it in the same number of cycles, so the pair count and the
// operand-wait events pin exactly one thing — what an adjacent dependency costs at each bypass level.
//
// Assemble (nzea):   just asm-elf dep_chain riscv32im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/dep_chain-riscv32im.elf \
//                      --isa riscv32im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ func trace wave-form on }' and '{ continue }' \
//                      and '{ stat print }' --difftest remu

    .section .text
    .globl _start
_start:
    li      t0, 0
    li      t1, 0

    .rept 64
    addi    t0, t0, 1         // write t0
    add     t1, t0, zero      // read t0: the pair's consumer
    .endr

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass