# Lab 2.1 报告：质因数分解的参数对比

## 1. 实验输入

学号 `2026E8013282144`，按讲义要求把非数字字符替换为 `0`：

| 输入 | 数值 | 质因数分解 |
|---|---|---|
| 原学号 | 202608013282144 | 2^5 × 928423 × 6819629 |
| 反写学号 | 441282310806202 | 2 × 6143603 × 35913967 |

被测程序为 `factor.c`：从 `argv[1]` 读入一个十进制数，用试除法从小到大输出质因数。编译命令 `gcc -O2 -o factor factor.c`。两次输入使用完全相同的二进制与参数。

## 2. 实验方法

配置脚本 `lab21.py` 由 `configs/learning_gem5/part1/simple.py` 改写，只把 CPU 型号、时钟频率和 DRAM 型号变成命令行参数。基线为：

- CPU：`X86TimingSimpleCPU`
- 频率：1 GHz
- 内存：`DDR3_1600_8x8`
- 系统：SE 模式，512 MiB，无 L1/L2 cache（与 `simple.py` 一致）

每次运行使用独立输出目录，保留 `stats.txt` 与 `config.ini`。模拟命令形如：

```sh
./build/X86/gem5.opt -d lab2/<dir> lab2/lab21.py \
    --cpu TimingSimpleCPU --freq 1GHz --mem DDR3_1600_8x8 \
    --binary /workspace/gem5-lab/lab2/factor --args 202608013282144
```

## 3. 结果

| # | 输入 | CPU | 频率 | 内存 | simSeconds (s) | simInsts | hostSeconds (s) |
|---|---|---|---|---|---|---|---|
| 1 | 原学号 | TimingSimpleCPU | 1 GHz | DDR3-1600 | 0.774407 | 12,169,354 | 23.20 |
| 2 | 反写学号 | TimingSimpleCPU | 1 GHz | DDR3-1600 | 5.077102 | 79,966,658 | 155.90 |
| 3 | 反写学号 | MinorCPU | 1 GHz | DDR3-1600 | 3.596959 | 79,966,714 | 1007.12 |
| 4 | 反写学号 | TimingSimpleCPU @ 2 GHz | 2 GHz | DDR3-1600 | 5.077102 | 79,966,658 | 156.04 |
| 5 | 反写学号 | TimingSimpleCPU | 1 GHz | DDR3-2133 | 4.862854 | 79,966,658 | 160.55 |

第 1 组用来确定「较慢的输入」：反写学号比原学号慢 6.6 倍。第 2～5 组固定在同一输入上，每次只改变一个因素。

## 4. 分析

### 4.1 输入的影响

两个输入执行的是同一段代码，`simInsts` 之比为 79,966,658 / 12,169,354 = 6.57，`simSeconds` 之比为 5.077102 / 0.774407 = 6.56，二者几乎相同。也就是说两次运行的 CPI 基本一致（约 63.5），时间差完全来自试除循环的迭代次数：

- 原学号在 `p = 928423` 处找到第二个因子，循环约 93 万次；
- 反写学号要把 `p` 试到 6,143,603 才找到因子，循环约 614 万次，正好 6.6 倍。

### 4.2 CPU 型号

`MinorCPU` 把 `simSeconds` 从 5.077 s 降到 3.597 s（−29.1%），指令数几乎不变（79,966,714 vs 79,966,658，差 56 条来自启动路径）。`TimingSimpleCPU` 对每条指令只做固定延迟建模、访存时整条流水线停等；`MinorCPU` 是顺序流水线模型，取指、执行和访存能部分重叠，因此同样的指令流耗时更短。

代价在宿主侧：`MinorCPU` 的 `hostSeconds` 是 1007 s，约为 `TimingSimpleCPU`（156 s）的 6.5 倍——模型越细，模拟开销越大。

### 4.3 CPU 频率

只把 CPU 时钟域从 1 GHz 提到 2 GHz（系统与 DRAM 保持 1 GHz）时，`simSeconds` 与 1 GHz 组**逐位相同**（5.077102 s，`simTicks` 都是 5,077,101,724,000）。也就是说在 `TimingSimpleCPU` + 无 cache 的配置里，CPU 自身的执行时间在 tick 精度下可以忽略，时间全部花在 DRAM 等待上。

作为对照，如果把整个系统时钟（含 membus 和 DRAM 控制器）一起翻倍，`simSeconds` 会从 5.077 s 降到 4.563 s（−10.1%）。这 10% 来自内存时序随控制器时钟变快，而不是 CPU 算得更快。两种做法的对比正好说明了瓶颈位置：负载对 CPU 频率不敏感，对访存延迟敏感。

`hostSeconds` 基本不变（155.9 s → 156.0 s），因为指令数和模拟工作量没有变化。

### 4.4 内存型号

`DDR3_2133_8x8` 把 `simSeconds` 从 5.077 s 降到 4.863 s（−4.2%）。`config.ini` 中两者的时序参数为：

| 参数 | DDR3-1600 | DDR3-2133 |
|---|---|---|
| `tCL` | 13750 ps | 13090 ps |
| `tRCD` | 13750 ps | 13090 ps |
| `tRP` | 13750 ps | 13090 ps |

CAS 延迟降低 4.8%，与模拟时间的降幅 4.2% 基本对应。这进一步说明该配置下访存延迟是主要瓶颈：换内存比换 CPU 型号/频率的影响小，但方向一致且可量化。

### 4.5 模拟时间与真实时间

`simSeconds` 是被模拟机器经过的时间，`hostSeconds` 是本次模拟在宿主机上的实际开销，两者口径不同：

- 比较不同配置性能时看 `simSeconds`；
- 评估仿真本身的成本时看 `hostSeconds`。

例如 `MinorCPU` 让 `simSeconds` 降低 29%，却让 `hostSeconds` 增加 5.5 倍。两者不能混为一个「运行时间」。

## 5. 结论

1. 同一程序下，输入规模决定指令数，`simSeconds` 与指令数近似成正比。
2. 在无 cache 的 `simple.py` 基线上，性能由 DRAM 延迟主导：只提 CPU 频率没有可测收益（tick 数逐位相同），换更快的内存约 4%，而更精细的 CPU 模型约 29%。
3. 因此「改配置后为什么变」不能只看 CPU 参数，必须结合访存路径和缓存配置解释；`stats.txt` 里的 `simInsts`、`simSeconds` 与 `config.ini` 的时序参数足以支撑这一结论。

## 6. 附件

- `TimingSimpleCPU-stats.txt`：第 2 组（反写学号，TimingSimpleCPU，1 GHz，DDR3-1600）
- `MinorCPU-stats.txt`：第 3 组（反写学号，MinorCPU，1 GHz，DDR3-1600）
- 配置脚本 `lab21.py`、程序 `factor.c`、自动化脚本 `run_lab21.sh`
