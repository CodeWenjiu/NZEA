// DDR3 small memtest (64 words) — same as the original, for stability A/B.
// UART @ 0x10000000, finisher @ 0x00100000.
.equ N, 64
.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr
    li      s3, 0
    li      s4, N
    lui     s5, 0x88000
    li      s6, 0xDEADBEEF
wr_loop:
    xor     s7, s6, s3
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)
    addi    s3, s3, 1
    bltu    s3, s4, wr_loop
    la      a0, msg_wr
    jal     putstr
    li      s3, 0
rd_loop:
    slli    s1, s3, 2
    add     s1, s5, s1
    lw      s2, 0(s1)
    xor     s7, s6, s3
    bne     s2, s7, rd_fail
    addi    s3, s3, 1
    bltu    s3, s4, rd_loop
    la      a0, msg_ok
    jal     putstr
    j       done
rd_fail:
    la      a0, msg_f
    jal     putstr
    mv      a0, s3
    jal     puthex
    li      a1, ' '
    jal     putc
    mv      a0, s2
    jal     puthex
    li      a1, '\n'
    jal     putc
done:
    j       done

.section .rodata
msg_start: .asciz "SMALL start\n"
msg_wr:    .asciz "WR\n"
msg_ok:    .asciz "OK\n"
msg_f:     .asciz "FAIL@"
