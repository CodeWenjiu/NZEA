// Arm frame residency: the arm block hangs off an off-chip I2C wire, and one access is one frame.
//
// nzea_rtl's residency (`LiteBusResidency`, table in `nzea_device.DeviceFrames`) sits in front of the peripheral
// window: every access to the arm range (0x8A00_0000+0x68) is held for the frame's own arithmetic — 20 bits at
// 400 kHz against the tile's 25 MHz clock = 1250 cycles. The contract is transparent: reads and writes alike pay a
// frame. Difftest checks this program functionally (the window is MMIO, so the reference follows the DUT's
// observations); the timing itself is pinned on the waveform — `func trace wave-form on` below, then pulse on
// `target/trace.fst` under the remu tree, where each arm access shows the residence: request fire to response
// valid = 1250 cycles.
//
// Assemble (nzea):   just asm-elf arm_frame riscv32im
// Run (remu shell, cwd = this repo's root):
//   cargo run --manifest-path ../remu/Cargo.toml -p remu_cli --release -- \
//     --elf build/asm/arm_frame-riscv32im.elf --isa riscv32im \
//     --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 \
//     --dev-addon ../remu/configs/devices/arm.conf \
//     --batch --startup '{ func trace wave-form on } and { continue }' --difftest remu
//
// Exit: the SiFive test finisher (0x0010_0000) gets 0x5555, or (code << 16) | 0x3333 when a check fails.

    .section .text
    .globl _start
_start:
    li      t0, 0x8A000000          // the arm block's register file
    lw      t1, 0x00(t0)            // NU — one frame
    beqz    t1, fail_1

    lw      t5, 0x30(t0)            // QPOS[0] — one frame; the value is the model's, only the access is on trial

    li      t2, 0x3F000000          // 0.5f
    sw      t2, 0x08(t0)            // TARGET[0] = 0.5 — one frame
    lw      t3, 0x08(t0)            // read it back — one frame
    bne     t3, t2, fail_2

    lw      t6, 0x04(t0)            // NQ — one frame
    beqz    t6, fail_3

pass:
    li      t0, 0x00100000
    li      t1, 0x5555
    sw      t1, 0(t0)
    j       pass

fail_1:
    li      a0, 1
    j       fail
fail_2:
    li      a0, 2
    j       fail
fail_3:
    li      a0, 3
    j       fail

fail:
    slli    a0, a0, 16
    li      t1, 0x3333
    or      a0, a0, t1
    li      t0, 0x00100000
    sw      a0, 0(t0)
    j       fail