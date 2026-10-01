// Word-form division probe (RV64 only): DIVW/DIVUW/REMW/REMUW over lows of both signs, plus the 32-bit special cases
// (a zero divisor, INT32_MIN / -1), with results sign-extended to 64 bits.
//
// Verified under remu difftest (riscv64im, 419 cycles / 20 commits): the DIVW fault seen earlier was a remu-side
// decode bug, since fixed. The plain forms are covered by div_probe.S on both widths.
//
// Assemble (nzea):   just asm-elf divw_probe riscv64im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/divw_probe-riscv64im.elf \
//                      --isa riscv64im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ continue } and { stat print }' --difftest remu

    .section .text
    .globl _start
_start:
    // Low-word division over both signs.
    li      t0, 0x00000000deadbeef
    li      t1, -3
    divw    t2, t0, t1
    remw    t3, t0, t1
    divuw   t4, t0, t1
    remuw   t5, t0, t1

    // The 32-bit special cases read on the low words, results sign-extended.
    li      t0, 0xffffffff80000000
    li      t1, -1
    divw    t2, t0, t1
    remw    t3, t0, t1
    li      t1, 0
    divw    t2, t0, t1
    divuw   t4, t0, t1
    remw    t3, t0, t1
    remuw   t5, t0, t1

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass