// Row-5 hammer with LONG read-back delay: after each partial-write RMW, wait
// 10000 cycles before verifying. Distinguishes "write never landed" (still
// fails after long delay) from "read too early / drain insufficient" (passes
// after delay). Loops forever; FAIL on mismatch.

    .section .text
    .globl _start
_start:
    la      a0, msg_start
    jal     putstr

    lui     s5, 0x88000
    li      s4, 5
    slli    s1, s4, 4
    add     s1, s5, s1             // s1 = 0x88000050 (row 5)
    li      s10, 0                 // iteration counter

loop:
    // full-word write row 5 = 0xDEADBEEF ^ i
    li      s3, 0
    li      s6, 0xDEADBEEF
wr5:
    xor     s7, s6, s3
    slli    s2, s3, 2
    add     s2, s1, s2
    sw      s7, 0(s2)
    addi    s3, s3, 1
    li      s4, 4
    bltu    s3, s4, wr5

    // partial write row 5
    li      s6, 0xAA
    sb      s6, 0(s1)
    li      s6, 0x5F
    sb      s6, 8(s1)
    li      s6, 0xBEEF
    sh      s6, 4(s1)
    li      s6, 0xF00D
    sh      s6, 12(s1)

    // long delay: 10000 cycles
    li      s3, 0
delay:
    addi    s3, s3, 1
    li      s4, 10000
    bltu    s3, s4, delay

    // verify
    lbu     s2, 0(s1)
    li      s7, 0xAA
    bne     s2, s7, fail
    lbu     s2, 8(s1)
    li      s7, 0x5F
    bne     s2, s7, fail
    lhu     s2, 4(s1)
    li      s7, 0xBEEF
    bne     s2, s7, fail
    lhu     s2, 12(s1)
    li      s7, 0xF00D
    bne     s2, s7, fail

    addi    s10, s10, 1
    andi    s3, s10, 0x3F
    bnez    s3, loop
    li      a0, '.'
    jal     putc
    j       loop

fail:
    mv      s8, s2
    mv      s9, s7
    la      a0, msg_f
    jal     putstr
    mv      a0, s10
    jal     puthex
    li      a1, ' '
    jal     putc
    mv      a0, s8
    jal     puthex
    li      a1, ' '
    jal     putc
    mv      a0, s9
    jal     puthex
    li      a1, '\n'
    jal     putc
    j       loop

    .section .rodata
msg_start: .asciz "R5DL\n"
msg_f:     .asciz "FAIL it="
