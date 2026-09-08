// Common UART + debug helpers shared by all built-in DDR test programs.
// Stack-free: putc/putstr clobber t-regs only; puthex clobbers t-regs and s0.
// Appended to each test .S by asm2hex before assembly. Tests must keep loop
// state in s-registers and define their own `.rodata` messages.

.section .text

putc:                               // a1 = byte
    mv      t5, ra
    lui     t0, 0x10000             // UART THR @ 0x10000000
    sw      a1, 0(t0)
    jr      t5

putstr:                             // a0 = string (NUL-terminated)
    mv      t6, ra
1:  lbu     a1, 0(a0)
    beqz    a1, 2f
    jal     putc
    addi    a0, a0, 1
    j       1b
2:  jr      t6

puthex:                             // a0 = value (8 hex digits)
    mv      s0, ra
    li      t6, 7
1:  slli    t1, t6, 2
    srl     t2, a0, t1
    andi    t2, t2, 0xf
    li      t3, 10
    bltu    t2, t3, 2f
    addi    t2, t2, -10
    addi    a1, t2, 'A'
    j       3f
2:  addi    a1, t2, '0'
3:  jal     putc
    addi    t6, t6, -1
    bgez    t6, 1b
    jr      s0
