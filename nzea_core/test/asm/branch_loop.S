// Branch-loop timing probe: a fixed loop count around a taken backward branch, with one load/store pair inside.
//
// The baseline predicts the fall-through, so every taken branch is a mispredict and a redirect — this program is a
// deterministic stream of flushes with a deterministic instruction count, which is what makes it a clean probe for
// pipeline changes: the same RTL shape must retire it in exactly the same number of cycles.
//
// Assemble (nzea):   just asm-elf branch_loop riscv32im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/branch_loop-riscv32im.elf \
//                      --isa riscv32im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ continue } and { stat print }' --difftest remu

    .section .text
    .globl _start
_start:
    li      t0, 32            // iterations
    li      t1, 0             // accumulator
    li      t2, 0x80000000    // RAM scratch
    li      t3, 1

loop:
    add     t1, t1, t3
    sw      t1, 0(t2)
    lw      t4, 0(t2)
    addi    t0, t0, -1
    bne     t0, zero, loop    // taken: one mispredict per iteration

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass