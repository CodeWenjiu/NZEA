// Pure read-only test: write known values once, then repeatedly READ them back
// to detect intermittent read-path corruption WITHOUT any RMW write-back in the
// read loop.
//
// Motivation: board failures (`P1 FAIL got=0xAAAA5555`, `P2 FAIL got=0xFF`)
// were initially blamed on "RMW write-back not landing", but ILA capture showed
// every write-back handshake is clean (wdf pulses all present). This test
// isolates whether the READ path itself returns wrong data: if pure reads of
// known-good data occasionally return 0xFF/0xAAAA/0xAAAA5555, the corruption is
// in the MIG read datapath / DDR physical layer, not the adapter write path.
//
// Plan:
//   1. Write rows 0-3 (16 words) with 0xDEADBEEF ^ i (full-word sw, wstrb=0xF).
//   2. Loop MANY times: read all 16 words, compare to expected, count errors.
//      On any mismatch, print ADDR GOT EXP and stop early.
//   3. After the loop (no errors), print "RDOk <count>".
//
// Register discipline: loop state in s-registers (puthex clobbers t-regs + s0).

.equ N, 16               // words to test (rows 0-3)
.equ LOOPS, 40000        // read passes

.section .text
.globl _start
_start:
    la      a0, msg_start
    jal     putstr

    // ---- phase 1: write [0x88000000 + 4i] = 0xDEADBEEF ^ i ----
    li      s3, 0                 // i
    li      s4, N
    lui     s5, 0x88000           // DDR base
    li      s6, 0xDEADBEEF        // pattern base
wr_loop:
    xor     s7, s6, s3            // pattern
    slli    s1, s3, 2
    add     s1, s5, s1
    sw      s7, 0(s1)
    addi    s3, s3, 1
    bltu    s3, s4, wr_loop

    la      a0, msg_wr_done
    jal     putstr

    // ---- phase 2: repeated pure reads ----
    li      s10, 0                // pass counter
rd_pass:
    li      s3, 0                 // word index
rd_loop:
    slli    s1, s3, 2
    add     s1, s5, s1
    lw      s2, 0(s1)             // got
    xor     s7, s6, s3            // expected
    bne     s2, s7, rd_fail
    addi    s3, s3, 1
    bltu    s3, s4, rd_loop
    addi    s10, s10, 1
    li      s11, LOOPS
    bltu    s10, s11, rd_pass

    // ---- all clean ----
    la      a0, msg_ok
    jal     putstr
    mv      a0, s10
    jal     puthex
    li      a1, '\n'
    jal     putc
    lui     t0, 0x00100
    sw      x0, 0(t0)             // finisher
    j       done

rd_fail:
    // s3 = word index, s2 = got, s7 = expected.
    la      a0, msg_fail
    jal     putstr
    // addr = 0x88000000 + 4*s3
    mv      a0, s3
    jal     puthex
    li      a1, ' '
    jal     putc
    la      a0, msg_got
    jal     putstr
    mv      a0, s2
    jal     puthex
    li      a1, ' '
    jal     putc
    la      a0, msg_exp
    jal     putstr
    mv      a0, s7
    jal     puthex
    li      a1, '\n'
    jal     putc
    lui     t0, 0x00100
    li      t1, 1
    sw      t1, 0(t0)             // finisher: error
    j       done

done:
1:  j       1b

    .section .rodata
msg_start: .asciz "RDONLY start\n"
msg_wr_done: .asciz "WRDONE\n"
msg_ok:     .asciz "RDOk passes="
msg_fail:   .asciz "FAIL word="
msg_got:    .asciz "got="
msg_exp:    .asciz "exp="
