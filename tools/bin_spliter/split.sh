# 获取程序名称
program_name=$(basename "$1")

riscv64-linux-gnu-objcopy -O binary --only-section=.text  "$program_name" irom.bin
riscv64-linux-gnu-objcopy -O binary --only-section=.data  "$program_name" dram.bin
