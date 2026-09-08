// DDR3 write-verify probe — for each word, write then IMMEDIATELY read back.
// Distinguishes "write never landed" (immediate read fails) from "write
// delayed" (immediate read ok, later read fails). N=1024 words.
.equ N, 1024
.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr

    li      s3, 0
    li      s4, N
    lui     s5, 0x88000
    li      s6, 0xDEADBEEF

wv_loop:
    xor     s7, s6, s3            // pattern
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)             // write
    lw      s2, 0(s1)             // immediate read-back
    bne     s2, s7, wv_fail
    addi    s3, s3, 1
    bltu    s3, s4, wv_loop

    la      a0, msg_ok
    jal     putstr
    j       done

wv_fail:
    la      a0, msg_f
    jal     putstr
    mv      a0, s3
    jal     puthex
    li      a1, ' '
    jal     putc
    mv      a0, s2
    jal     puthex
    li      a1, ' '
    jal     putc
    mv      a0, s7
    jal     puthex
    li      a1, '\n'
    jal     putc
done:
    j       done

.section .rodata
msg_start: .asciz "WV start\n"
msg_ok:    .asciz "WV OK\n"
msg_f:     .asciz "WV FAIL i="
