// Print-only smoke test: no DDR access at all. If THIS hangs, the problem is
// the program/load/UART, not DDR.

    .section .text
    .globl _start
_start:
    la      a0, msg_hello
    jal     putstr
    li      a0, 0
    j       finisher

finisher:
    li      t0, 0x100000
    sw      a0, 0(t0)
1:  j       1b

    .section .rodata
msg_hello: .asciz "HELLO\n"
