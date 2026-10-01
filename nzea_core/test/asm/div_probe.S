// Division probe: DIV/DIVU/REM/REMU across sign combinations and RISC-V's special cases (a zero divisor, the signed
// overflow), in one straight-line stream with a poll loop at the end. Difftest is the checker — every committed
// register value is compared against the reference, so a wrong quotient, remainder or special answer fails the run.
// The 64-bit build carries the 64-bit cases as well; the word forms live in divw_probe.S.
//
// Assemble (nzea):   just asm-elf div_probe riscv32im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/div_probe-riscv32im.elf \
//                      --isa riscv32im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ continue } and { stat print }' --difftest remu

    .section .text
    .globl _start
_start:
    // Mixed signs, truncating division.
    li      t0, -1234567
    li      t1, 89
    div     t2, t0, t1
    rem     t3, t0, t1
    divu    t4, t0, t1
    remu    t5, t0, t1

    // Dividend smaller than the divisor, and exact division.
    li      t0, 5
    li      t1, 7
    div     t2, t0, t1
    rem     t3, t0, t1
    li      t0, -81
    li      t1, 9
    div     t2, t0, t1
    rem     t3, t0, t1

.if __riscv_xlen == 32
    // The signed overflow: INT_MIN / -1, and its remainder.
    li      t0, 0x80000000
    li      t1, -1
    div     t2, t0, t1
    rem     t3, t0, t1

    // A zero divisor: DIV answers -1, DIVU all ones, both remainders the dividend.
    li      t1, 0
    div     t2, t0, t1
    divu    t4, t0, t1
    rem     t3, t0, t1
    remu    t5, t0, t1

    // A negative dividend against the unsigned forms.
    li      t0, -7
    li      t1, 2
    divu    t4, t0, t1
    remu    t5, t0, t1
.else
    // The 64-bit overflow and zero divisor.
    li      t0, 0x8000000000000000
    li      t1, -1
    div     t2, t0, t1
    rem     t3, t0, t1
    li      t1, 0
    div     t4, t0, t1
    rem     t5, t0, t1

    // Wide magnitudes.
    li      t0, 0x0000000123456789
    li      t1, 97
    div     t2, t0, t1
    rem     t3, t0, t1
    li      t0, -0x123456789
    li      t1, 1000
    div     t2, t0, t1
    rem     t3, t0, t1
.endif

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass