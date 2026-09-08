// DDR3 row-1/2 focus probe — repeatedly write+read the 0x88000000..0x8800004F
// region (rows 0..4) to check if the FAIL@8/FAIL@16 area is deterministic.
// Loops forever; watch for mismatches. UART @ 0x10000000.
.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr

    lui     s5, 0x88000
    li      s6, 0xDEADBEEF
    li      s10, 0                // iteration counter

loop:
    // write rows 0..4 (20 words)
    li      s3, 0
wr:
    xor     s7, s6, s3
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)
    addi    s3, s3, 1
    li      s4, 20
    bltu    s3, s4, wr

    // read back + verify
    li      s3, 0
rd:
    slli    s1, s3, 2
    add     s1, s5, s1
    lw      s2, 0(s1)
    xor     s7, s6, s3
    bne     s2, s7, fail
    addi    s3, s3, 1
    li      s4, 20
    bltu    s3, s4, rd

    addi    s10, s10, 1
    // print progress every 50 iterations
    andi    s1, s10, 0x3F
    bnez    s1, loop
    mv      a0, s10
    jal     puthex
    li      a1, ' '
    jal     putc
    j       loop

fail:
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
    j       loop

.section .rodata
msg_start: .asciz "FOCUS start\n"
msg_f:     .asciz "FAIL i="
