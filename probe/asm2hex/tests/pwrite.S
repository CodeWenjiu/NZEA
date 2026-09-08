// DDR3 partial-write probe — seed one row with 0xDEADBEEF per word, then do
// sb/sh on specific bytes, read back the full row and print all 4 words.
// UART @ 0x10000000.
.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr

    lui     s5, 0x88000
    li      s6, 0xDEADBEEF
    // seed row 0: words 0..3
    sw      s6, 0(s5)
    sw      s6, 4(s5)
    sw      s6, 8(s5)
    sw      s6, 12(s5)
    la      a0, msg_seed
    jal     putstr

    // sb byte0 = 0x11
    li      s6, 0x11
    sb      s6, 0(s5)
    // sh half[4:5] = 0x2233
    li      s6, 0x2233
    sh      s6, 4(s5)
    la      a0, msg_pw
    jal     putstr

    // read back 4 words
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
msg_start: .asciz "PWR start\n"
msg_seed:  .asciz "SEED\n"
msg_pw:    .asciz "PARTIAL\n"
