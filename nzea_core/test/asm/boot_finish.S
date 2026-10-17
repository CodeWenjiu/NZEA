// The board's boot smoke test: write the finisher, then spin.
//
// The first program the board's loader is asked to carry. Its only job is to be *observable without a UART
// transmitter*: the SiFive test finisher's window (`0x00100000`) raises the tile's `finished` pin, which the top
// spends on LED2 (steady once written) and the ILA watches — so a correct load shows as a light.
//
// Assemble (probe crate):  cargo run -p asm2hex -- nzea_core/test/asm/boot_finish.S build/boot_finish.hex
// Load (host tool):        probe load build/boot_finish.hex --addr 0x80000000
// Restart the resident copy: probe reset --addr 0x80000000
//
// The simulator bench (`nzea_fpga/sim/BootLoad.sv`) sends the same four words inline; keep the two in step.

    .section .text
    .globl _start
_start:
    li      t0, 0x00100000    // the finisher's window
    li      t1, 1
    sw      t1, 0(t0)

1:  j       1b
