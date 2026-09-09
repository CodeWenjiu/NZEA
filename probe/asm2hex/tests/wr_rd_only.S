// Minimal DDR test: full-word write + immediate readback only.
// Isolates "basic R/W" from "partial-write / RMW" - if this fails too, the
// problem is in the fundamental write/read path, not the RMW merge.

    .section .text
    .globl _start
_start:
    // ---- phase 1: write [0x88000000 + 4i] = 0xDEADBEEF ^ i ----
    li      s3, 0                   // i
    li      s4, 1024                // N
    lui     s5, 0x88000             // DDR base
    li      s6, 0xDEADBEEF          // pattern base
wr_loop:
    xor     s7, s6, s3              // pattern
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)
    addi    s3, s3, 1
    bltu    s3, s4, wr_loop

    la      a0, msg_wr_done
    jal     putstr

    // ---- phase 2: readback + compare ----
    li      s3, 0
rd_loop:
    slli    s1, s3, 2
    add     s1, s5, s1
    lw      s2, 0(s1)               // got
    xor     s7, s6, s3              // expected
    bne     s2, s7, rd_fail
    addi    s3, s3, 1
    bltu    s3, s4, rd_loop

    la      a0, msg_rd_ok
    jal     putstr
    li      a0, 0
    j       finisher

rd_fail:
    la      a0, msg_rd_fail
    jal     putstr
    mv      a0, s3
    jal     puthex
    la      a0, msg_got
    jal     putstr
    mv      a0, s2
    jal     puthex
    la      a0, msg_exp
    jal     putstr
    mv      a0, s7
    jal     puthex
    li      a0, '\n'
    jal     putc
    li      a0, 1
    j       finisher

finisher:
    li      t0, 0x100000
    sw      a0, 0(t0)
1:  j       1b

    .section .rodata
msg_wr_done: .asciz "WRDONE\n"
msg_rd_ok:   .asciz "RDOK\n"
msg_rd_fail: .asciz "FAIL @"
msg_got:     .asciz " got="
msg_exp:     .asciz " exp="
