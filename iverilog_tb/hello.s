# Test program: print "Hello_World" to UART, then trigger finisher.
# UART now has a TX hold buffer (thrPending) so no software delay needed.
# Assemble with: riscv64-unknown-elf-as -march=rv32i -o hello.o hello.s
#                riscv64-unknown-elf-objcopy -O binary hello.o hello.bin
#                od -An -tx4 -w4 hello.bin > hello.hex

.section .text
.globl _start
_start:
    lui t0, 0x10000            # UART base = 0x10000000

    addi t1, zero, 0x48       # 'H'
    sw   t1, 0(t0)

    addi t1, zero, 0x65       # 'e'
    sw   t1, 0(t0)

    addi t1, zero, 0x6C       # 'l'
    sw   t1, 0(t0)

    addi t1, zero, 0x6C       # 'l'
    sw   t1, 0(t0)

    addi t1, zero, 0x6F       # 'o'
    sw   t1, 0(t0)

    addi t1, zero, 0x5F       # '_'
    sw   t1, 0(t0)

    addi t1, zero, 0x57       # 'W'
    sw   t1, 0(t0)

    addi t1, zero, 0x6F       # 'o'
    sw   t1, 0(t0)

    addi t1, zero, 0x72       # 'r'
    sw   t1, 0(t0)

    addi t1, zero, 0x6C       # 'l'
    sw   t1, 0(t0)

    addi t1, zero, 0x64       # 'd'
    sw   t1, 0(t0)

    lui t2, 0x00100           # finisher base = 0x00100000
    sw  zero, 0(t2)           # trigger finisher

loop:
    j loop
