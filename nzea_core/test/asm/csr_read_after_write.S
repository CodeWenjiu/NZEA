// CSR read-after-write chains: a read must see a write made by the instruction ahead of it.
//
// The chains are back to back on purpose: `csrw` immediately followed by `csrr` is the sharpest case, because the read
// lands in the cycle the write commits. With an execute-stage bypass link the read window is one cycle ahead of the
// commit port's, and a CSR file that only reads its own registers would answer one instruction stale. That hazard
// needs the middle to admit the pair on consecutive cycles, which needs the front end to hand it two instructions on
// consecutive cycles — today's fetcher paces one word per two cycles with no buffer, so the pair always has a gap and
// the chain is correct either way. This program is the regression net for both: it pins the chains' values now, and it
// is what fails first once a fetch buffer (or a 64-bit fetch word split in two) lets the pair land back to back.
//
// Assemble (nzea):   just asm-elf csr_read_after_write riscv32im   # or riscv64im
// Run (remu shell):  cargo run -p remu_cli --release -- --elf build/asm/csr_read_after_write-riscv32im.elf \
//                      --isa riscv32im --platform nzea --sim-opt target=core --sim-opt watchdog=3x10 \
//                      --batch --startup '{ continue }' --difftest remu
//
// Exit: the SiFive test finisher (0x0010_0000) gets 0x5555, or (code << 16) | 0x3333 when a check fails. The difftest
// is the real judge — a stale read reaches a GPR and mismatches remu's reference on the committing instruction.

    .section .text
    .globl _start
_start:
    // 1. csrw then csrr back to back; the read must see 0x12345678
    li      t0, 0x12345678
    csrw    mscratch, t0
    csrr    t1, mscratch
    bne     t0, t1, fail_1

    // 2. the same with another value
    li      t2, 0x0f0f0f0f
    csrw    mscratch, t2
    csrr    t3, mscratch
    bne     t2, t3, fail_2

    // 3. csrrw writes and answers the old value; the following read must see the new one
    li      t4, 0x00ff00ff
    csrrw   t5, mscratch, t4
    csrr    t6, mscratch
    bne     t4, t6, fail_3
    li      s0, 0x0f0f0f0f
    bne     t5, s0, fail_4

    // 4. csrrs sets bits; the immediate read must see them (0x00ff00ff | 0x0000ff00)
    li      s1, 0x0000ff00
    csrrs   zero, mscratch, s1
    csrr    s2, mscratch
    li      s3, 0x00ffffff
    bne     s2, s3, fail_5

    // 5. csrrc clears bits; the immediate read must see them (0x00ffffff & ~0x000000ff)
    li      s4, 0x000000ff
    csrrc   zero, mscratch, s4
    csrr    s5, mscratch
    li      s6, 0x00ffff00
    bne     s5, s6, fail_6

    // 6. csrrwi writes an immediate
    csrrwi  zero, mscratch, 0x1f
    csrr    s7, mscratch
    li      s8, 0x1f
    bne     s7, s8, fail_7

    // 7. the same chain on the other CSRs the file implements
    li      s9, 0x00001000
    csrw    mtvec, s9
    csrr    s10, mtvec
    bne     s9, s10, fail_8

    li      s11, 0x0000abc4
    csrw    mepc, s11
    csrr    t0, mepc
    bne     s11, t0, fail_9

    li      t1, 0x0b
    csrw    mcause, t1
    csrr    t2, mcause
    bne     t1, t2, fail_10

    // 8. a chain whose write data depends on the previous read
    li      t3, 0x40000000
    csrw    mscratch, t3
    csrr    t4, mscratch
    srli    t5, t4, 30
    csrrw   zero, mscratch, t5
    csrr    t6, mscratch
    bne     t5, t6, fail_11

    // 9. control: a gap between the write and the read was always safe
    li      s0, 0x0badf00d
    csrw    mscratch, s0
    nop
    csrr    s1, mscratch
    bne     s0, s1, fail_12

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
fail_4:
    li      a0, 4
    j       fail
fail_5:
    li      a0, 5
    j       fail
fail_6:
    li      a0, 6
    j       fail
fail_7:
    li      a0, 7
    j       fail
fail_8:
    li      a0, 8
    j       fail
fail_9:
    li      a0, 9
    j       fail
fail_10:
    li      a0, 10
    j       fail
fail_11:
    li      a0, 11
    j       fail
fail_12:
    li      a0, 12
    j       fail

fail:
    slli    a0, a0, 16
    li      t1, 0x3333
    or      a0, a0, t1
    li      t0, 0x00100000
    sw      a0, 0(t0)
    j       fail
