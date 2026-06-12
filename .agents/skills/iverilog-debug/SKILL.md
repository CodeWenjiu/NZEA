---
name: iverilog-debug
description: Step-by-step checklist for diagnosing deadlocked or failing iverilog simulations. Use when a simulation hangs, produces X values, or shows no commit progress.
---

# iverilog 仿真调试指南

## 超时 vs 死锁

在诊断失败的仿真时，始终从较短的超时（10 秒）开始，然后翻倍。**如果增加超时后提交计数始终停留在相同数字，说明仿真已死锁，而非运行缓慢。** 不要继续增加超时——应调查最后几条提交。

## 数据损坏检查清单

本项目中的死锁仿真可归为几类。按以下顺序检查：

### 1. 提交跟踪中出现 X

`rd=xN val=0xXXXX` 或 `next_pc=0xXXXX` 表示读取了未初始化的内存。最常见原因：

- Hex 文件加载时被截断（检查 `boot_from_hex` / `$readmemh` 中的哨兵值）
- BSS 段未清零（检查 `_start` 代码）
- 栈/堆溢出超出 RAM 末尾（检查 `x2` 的值与 `AddressMap.ram` 范围的比较）

### 2. 卡在同一 PC

死循环。检查该 PC 处的汇编代码。常见模式：

- **LSR 轮询**：`lbu aN, offset(t0); andi; beqz loop`——驱动程序与硬件寄存器映射之间的 `offset` 不匹配
- **自跳转**：`j .`——加载了错误的指令，或者指令正确但预期应该跳出循环

### 3. 完全没有提交

CPU 从未启动。检查：

- `cpu_running` 信号监控的是实际核心复位，而非 BootFsm 输出（直接启动模式下 BootFsm 可能被绕过）
- `boot_override` 是否针对启动模式正确设置

## Verilog 中的哨兵值

切勿使用 `0x00000000` 作为 hex 加载的哨兵值——它是一个有效的 RISC-V 指令。使用 `0xDEADBEEF` 或其他不会出现在编译程序中的值。
