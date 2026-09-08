// DDR3 partial-write probe v2 — seed one row, then sb/sh on HIGH-half bytes
// (byte 8, half [12:13]) and read back. Tests high-half partial writes.
// UART @ 0x10000000.
.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr

    lui     s5, 0x88000
    li      s6, 0xDEADBEEF
    sw      s6, 0(s5)
    sw      s6, 4(s5)
    sw      s6, 8(s5)
    sw      s6, 12(s5)
    la      a0, msg_seed
    jal     putstr

    // sb byte8 = 0x44
    li      s6, 0x44
    sb      s6, 8(s5)
    // sh half[12:13] = 0x5566
    li      s6, 0x5566
    sh      s6, 12(s5)
    la      a0, msg_pw
    jal     putstr

    lw      s2, 0(s5)
    mv      a0, s2
    jal     puthex
    li      a1, ' '
    jal     putc
    lw      s2, 4(s5)
    mv      a0, s2
    jal     puthex
    li      a1, ' '
    jal     putc
    lw      s2, 8(s5)
    mv      a0, s2
    jal     puthex
    li      a1, ' '
    jal     putc
    lw      s2, 12(s5)
    mv      a0, s2
    jal     puthex
    li      a1, '\n'
    jal     putc

done:
    j       done

.section .rodata
msg_start: .asciz "PWR2 start\n"
msg_seed:  .asciz "SEED\n"
msg_pw:    .asciz "PARTIAL\n"
