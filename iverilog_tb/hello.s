# Minimal RISC-V test program for 4-state simulation.
# Assemble with: riscv64-unknown-elf-as -march=rv32i -o hello.o hello.s
#                riscv64-unknown-elf-objcopy -O binary hello.o hello.bin
#                od -An -tx4 -w4 hello.bin > hello.hex

.section .text
.globl _start
_start:
    li x1, 10
    li x2, 20
    add x3, x1, x2
    sub x4, x2, x1
loop:
    j loop
