// Partial-write scan: full-word-write N rows, then partial-write (sb/sh) each
// row, then verify strobed bytes AND un-strobed preservation. Isolates whether
// the phase-4 failure scales with row count / address range.
// Like memtest phase 3+4 but parameterized and with a cleaner report.
//
// Report on fail: "FAIL r=<row> w=<word> got=<val> exp=<val>" then row dump.
// Loop state in s-registers (putc/putstr/puthex clobber t-regs and s0).

    .section .text
    .globl _start
_start:
    // ---- phase A: full-word write [0x88000000 + 4i] = 0xDEADBEEF ^ i ----
    li      s3, 0
    li      s4, 1024               // N words = 64 rows
    lui     s5, 0x88000
    li      s6, 0xDEADBEEF
wr_a:
    xor     s7, s6, s3
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)
    addi    s3, s3, 1
    bltu    s3, s4, wr_a

    la      a0, msg_wa
    jal     putstr

    // ---- phase B: partial-write each 16B row ----
    // row r base 0x88000000 + 16r:
    //   sb byte 0 <- 0xA5 + r,  sb byte 8 <- 0x5A ^ r
    //   sh half[4:5] <- 0xBEEF,  sh half[12:13] <- 0xF00D
    li      s3, 0                  // row
    li      s8, 64                 // NROWS (match memtest scale)
pw_b:
    slli    s1, s3, 4
    add     s1, s5, s1
    addi    s6, s3, 0xA5
    andi    s6, s6, 0xFF
    sb      s6, 0(s1)
    li      s7, 0x5A
    xor     s6, s7, s3
    andi    s6, s6, 0xFF
    sb      s6, 8(s1)
    li      s6, 0xBEEF
    sh      s6, 4(s1)
    li      s6, 0xF00D
    sh      s6, 12(s1)
    addi    s3, s3, 1
    bltu    s3, s8, pw_b

    la      a0, msg_pb
    jal     putstr

    // ---- phase C: verify partial writes ----
    li      s3, 0                  // row
pv_c:
    slli    s1, s3, 4
    add     s1, s5, s1

    // byte 0 == 0xA5 + r
    lbu     s2, 0(s1)
    addi    s7, s3, 0xA5
    andi    s7, s7, 0xFF
    bne     s2, s7, pv_fail

    // byte 8 == 0x5A ^ r
    lbu     s2, 8(s1)
    li      s7, 0x5A
    xor     s7, s7, s3
    andi    s7, s7, 0xFF
    bne     s2, s7, pv_fail

    // half [4:5] == 0xBEEF
    lhu     s2, 4(s1)
    li      s7, 0xBEEF
    bne     s2, s7, pv_fail

    // half [12:13] == 0xF00D
    lhu     s2, 12(s1)
    li      s7, 0xF00D
    bne     s2, s7, pv_fail

    // word1 (offset 4) un-strobed bytes 6:7 == pattern(4r+1) bits [31:16]
    slli    s7, s3, 2
    addi    s7, s7, 1
    li      s9, 0xDEADBEEF
    xor     s7, s9, s7
    srli    s7, s7, 16
    lhu     s2, 6(s1)
    bne     s2, s7, pv_fail

    addi    s3, s3, 1
    bltu    s3, s8, pv_c

    la      a0, msg_ok
    jal     putstr
    li      a0, 0
    j       finisher

pv_fail:
    // s3=row, s2=got, s7=exp. Save all before printing.
    mv      s8, s3
    mv      s9, s2
    mv      s10, s7
    la      a0, msg_f
    jal     putstr
    mv      a0, s8
    jal     puthex
    li      a1, ' '
    jal     putc
    la      a0, msg_got
    jal     putstr
    mv      a0, s9
    jal     puthex
    li      a1, ' '
    jal     putc
    la      a0, msg_exp
    jal     putstr
    mv      a0, s10
    jal     puthex
    li      a1, '\n'
    jal     putc
    li      a0, 1
    j       finisher

finisher:
    li      t0, 0x100000
    sw      a0, 0(t0)
1:  j       1b

    .section .rodata
msg_wa:  .asciz "PWR_A\n"
msg_pb:  .asciz "PWR_B\n"
msg_ok:  .asciz "PVOK\n"
msg_f:   .asciz "PVFAIL r="
msg_got: .asciz " got="
msg_exp: .asciz " exp="
