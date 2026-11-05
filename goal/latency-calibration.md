# 延迟校准：参数出处与实测

Status: **A1–A3 完成（2026-11-01；2026-11-05 随 UART/I2C 进入 RTL 更新）**；A4 待决策（改值会动仿真基线）。
本文是 `research-plan.md` A 线的交付，随实现更新（不是历史记录）。

单位约定：未特别说明的"拍"= tile 时钟周期。仿真是 25 MHz（1 拍 = 40 ns），板卡是 100 MHz
（1 拍 = 10 ns）。

## 1. 延迟由谁来写（A2）

一条规则：**延迟由产生它的硬件自己声明，模型只保留模拟器独有的硬件**。

| 延迟的来源 | 谁表达 | 今天的例子 |
|---|---|---|
| 设备/线上的状态机 | 设备 RTL，两种流同一份 | UART 发送线（10 bit × 218 拍/字节）；I2C 帧（写 56 bit、读 66 bit，每 bit 62 拍 @25 MHz） |
| 只有模拟器才有的硬件 | 设备自己（`HasBridgeDelay`，默认 `None`） | RAM 的 `Delay(100 MHz, 23)`；display 窗口的 `Delay(25 MHz, 1)` |

两种流的分界因此只剩“线的终点”：RTL 在 `flow.bridges` 下把线接到 DPI（UART 的 `bus_write`，I2C 在周转点的
`bus_read`/`bus_write`），否则接到引脚。寄存器堆、发送器、帧状态机这些设备内部，两边是同一份 RTL。

### 为什么这两个延迟还在设备里

- **RAM**：板上的窗口是 512 MiB 的 DDR3（`nzea_fpga/logic/DdrMemory.scala` 的外部窗口），不是 tile 的
  128 KiB SRAM；桥接流里这块内存是 remu 的。DDR 控制器跑在板级 200/400 MHz 网格上，仿真只有一个时钟域，
  所以这段延迟只能是一个数（C1 实测值，见 §2）。
- **display**：remu 的帧缓冲/输入设备，窗口按普通内存对待（25 MHz 次级总线节奏），没有线要建模。

端口只声明一个延迟（`Delay`）；它怎么应用（延迟应答、占位、流水）是端口模块自己的逻辑。原先 arm 的 I2C 帧
已由 RTL 表达，所以“帧占线”这类逻辑现在都在设备里。

### 实测证据（I2C 帧，2026-11-05）

`I2cMasterTest`（iverilog）量到的帧长：写 56 bit、读 66 bit；bit 周期 = `clockHz / bitHz`（1 MHz/100 kHz
下是 10 拍），端口在整个帧内不接受新请求。

2,000,000 指令的 `robot_arm` 运行（25 MHz，difftest 0 失配）里，三个窗口的计数器：

| 窗口 | frames | busy | busy / frames | 推断 |
|---|---:|---:|---:|---|
| arm | 15 | 55,180 | 3,678.7 | 10 写 × 3,472 + 5 读 × 4,092 |
| scan | 135 | 552,420 | 4,092 | 全部是读 |
| motion | 4 | 16,368 | 4,092 | 全部是读 |

`busy` 恰好是帧长的整数倍（写 56 × 62 = 3,472，读 66 × 62 = 4,092），说明计数器数的是 RTL 的帧，不是模型的
估计。总量：I2C 线占 2M 指令的 624,968 / 3,846,833 = 16.2%，其中 scan 占 88%。

### 声明方式：一种延迟（设计规则）

延迟只有一种声明：`Delay(hz, cycles)`——`cycles` 个 `hz` 时钟周期。它在实例化处按总线时钟换算：
`at(busHz) = ⌈cycles × busHz ÷ hz⌉`，总线变快不会缩短硬件自己的时间。**这个值就是主设备看到的拍数**；
端口怎么应用它（延迟应答、占位、流水）是端口模块的逻辑，不是这个数的属性。

`SameDomain`（同域拍数）和 `Sum`（多段相加）已随这次简化删除：平台里已经没有“模拟器替身、且与总线同域”
的设备（这类设备的内部逻辑都在 RTL 里），DDR 通路也改成了 C1 的实测单值。`Clint` 的 `timeHz` 仍是频率
声明的一种，但它不是总线延迟。

这条规则由 `Delay.at` 强制：25 MHz / 100 MHz 下的等时性由 `LiteBusTest` 钉住。

## 2. 参数出处表（A1）

分类：**[S]** 标准/手册 · **[D]** 由帧格式推导 · **[R]** 由本仓 RTL 自身的应答距离标定 · **[H]** 由板级硬件事实推导
· **[N]** 无物理对应（该值只存在于仿真） · **[O]** 尚无出处。

**校准方法（2026-11-05 更新）**：延迟由产生它的硬件自己写。合成流里该设备 RTL 的请求→应答距离，就是桥接流
里同一份 RTL 给出的距离；只有模拟器独有的硬件才由设备自己声明延迟。逐个查：

| 设备 | RTL 的应答距离 | 出处 | 校准后 | 备注 |
|---|---:|---|---|---|
| CLINT | 1 拍 | `Clint.scala`：`req.ready := !busy` / `resp.valid := busy` | 无延迟（`None`） | 端口不建模型，模拟器的一级应答就是这一拍 |
| UART | 3 拍 + 2,180 拍/字节 | `Uart.scala`：`resp.valid := busCycle === 3`；发送器每字节 `10 × (clockHz / lineHz + 1)` 拍 | 无延迟（`None`）：两种流建同一份 RTL | 设备内部行为还给设备；桥接流只把线的终点换成 DPI |
| I2C（arm/scan/motion） | 写 56 bit / 读 66 bit | `I2cMaster.scala` 的帧状态机 | 无延迟（`None`）：两种流建同一份 RTL | 每 bit `clockHz / bitHz` 拍，端口在整个帧内不放行 |
| Finisher | 1 拍 | 单寄存器 `busy`（原三拍 `busCycle` 状态机已压掉，与 CLINT 同形） | 无延迟（`None`） | 端口不建模型 |
| 片上 RAM | 3 拍 | `RamFabricSlave.scala:116`（同一 `busCycle` 模式） | 不适用 | 板上这个窗口被 512 MiB 的 DDR3 外部窗口覆盖（`Top.scala` 的 `Window = "ram"`），所以本项代表的是 DDR3，见下 |

模型是确定性的：一次访问恰好是上表的拍数，没有随机抖动。

| 参数 | 值 | 位置 | 物理含义 | 出处 | 状态 |
|---|---|---|---|---|---|
| I2C 比特率 | 400,000 | `PlatformDevices.I2cHz` | 每条线的比特率 | **[S]** I2C Fast-mode 400 kbit/s（NXP UM10204 §3.1.3）；本仓 `doc/tms320f28p650dk.pdf` §1 特性同值 | 已校准 |
| I2C 帧位数（写 / 读） | 56 / 66 | `I2cMaster.scala` | 寄存器指针 + 4 数据字节（读再加重复起始） | **[D]** 1 + 9 + 9 + 4×9 + 1 = 56；读再加 1 + 9 = 66 | 已校准（RTL 自己算） |
| I2C 帧拍数 @25 MHz（写 / 读） | 3,472 / 4,092 | 同上 | 一帧占线时间 | **[R]** 25 MHz / 400 kHz = 62 拍/bit；实测 `I2cMasterTest` 与 2M 运行的 `busy/frames` 一致 | 已校准 |
| `RamFabricSlave.bridgeDelay` | `Delay(100 MHz, 23)` | `RamFabricSlave.scala` | 核心可见的整条 DDR 通路 | **[M]** 板上实测（C1，2026-11-04）：23 拍 × 10 ns = 230 ns 典型，54 拍最坏 | 已校准 |
| UART 发送线 | `10 bit × (clockHz / lineHz + 1)` | `Uart.scala` | 一字节 8N1 的占线时间 | **[D]** 1 start + 8 data + 1 stop = 10 bit；25 MHz / 115200 = 217，计数器重装再倒数使每 bit 实际 218 拍 → 2,180 拍/字节 | 已校准（RTL 自己算，不进模型） |
| finisher | 无延迟（`None`） | `SifiveTestFinisher.scala` | 退出钩子 | **[R]** RTL 1 拍（单寄存器）；端口不建模型 | 已校准（逐拍一致） |
| CLINT | 无延迟（`None`） | `Clint.scala` | 寄存器访问 | **[R]** RTL 1 拍；端口不建模型，直接是模拟器的一级应答 | 已校准（逐拍一致） |
| `PeripheralBridge.bridgeDelay` | `Delay(25 MHz, 1)` | `PeripheralBridge.scala` | 普通内存窗口的一次访问 | **[R]** 由窗口自己的两个速率推导（`PlatformDevices.PeripheralDeviceHz/Cycles`） | 已校准（25 MHz 下 1 拍） |
| `clockHz`（仿真） | 25 MHz | `NzeaTileParameter.scala:75` | tile 时钟 | **[P]** 基线参数 | 已明确 |
| `clockHz`（板卡） | 100 MHz | `nzea_fpga/src/Top.scala:103` ← `BootDdr.scala:82` | tile 时钟 | **[H]** `ClockOut(100.0, 0.0)` | 已明确 |
| `timeHz` | 1 MHz | `NzeaTileParameter.scala:76`、`Top.scala:104` | mtime 计数率 | **[O]** 与软件 10 MHz 假定冲突 | OPEN → §4 |

**三条结论：**

1. 两个模拟器替身延迟（RAM 与 display 窗口各自的 `bridgeDelay`）**只影响仿真**（消费点只有 `HasDpiBus.answerBus`，
   它在 `flow.bridges` 才走），它们代表的都是模拟器独有的硬件：板卡的 DDR3，与 remu 的帧缓冲/输入设备。CLINT、
   finisher 用 `HasBridgeDelay` 的默认 `None`（模拟器的一级应答），与各自 RTL 的单寄存器逐拍一致；UART 与 I2C 也没
   有——两种流建同一份 RTL，桥接流只把线的终点换成 DPI。
2. 板级**没有外设窗口**：`PlatformDevices` 的合成流清单只有 `ram`/`uart16550`/`finisher`/`clint`，没有
   `display`/`arm`/`scan`/`motion`——这些设备在板上不存在，它们的 RTL 目前只在桥接流里运行（引脚留给将来的
   真实传感器/驱动器）——**C2 被这一缺口阻塞**，不是被埋点阻塞。
3. DDR3 不再是“只有下界有源”：整条通路已在板上实测（C1，230 ns 典型），旧模型 `15 + 100 ns` 低估约一倍；
   详情见 §2 末尾的 C1 小节。

下表测于 2026-11-01，是当时 RAM 与桥两个模型的对比；桥那一列现在由 RTL 取代，RAM 敏感度的结论仍成立。

| 量 | RAM 20 拍 / 桥 4 拍 | RAM 3 拍 / 桥 2 拍 |
|---|---:|---:|
| `middle_cycle` | 3,539,903 | **3,047,996**（−13.9%） |
| `ipc` | 0.5650 | **0.6562**（+16.1%） |
| `ibus_stall` | 893,527 | **401,223**（−55.1%） |
| `icache_miss` | 25,135 | 25,142（不变） |
| `residency_frames` / `busy` | 15 / 18,750 | 15 / 18,750 |

两件事：

1. **寄存器文件的应答延迟不是成本所在** —— 上一轮把 8 改成 3/2 时总账只动了 −34 拍；UART 真正的成本
   在发送线上（见上面的基线）。
2. **RAM 延迟是这个模型里最敏感的单个参数** —— 17 拍的变化导致 ibus_stall 减半、总周期降 13.9%，
   因为每个 tick 有上万次 icache miss 要付这份延迟。所以 **C1 应当先测内存，而不是先测外设**；
   在外设窗口上花力气不如把 MIG 那 100 ns 换成实测值。

### C1 实测：板上的 DDR 访问（2026-11-04）

测量点：`nzea_fpga/src/logic/DdrMemory.scala` 的 `core` 端口（tile 看到的一侧）。计数器跑在 near clock
（板卡 100 MHz），只把保持值接到 ILA——ILA 按 50 MHz 采样，直接采 live 的 age 会与访问速率混叠。

| 探针 | 含义 |
|---|---|
| `ddrLatLast` | 最近一次核心访问的 fire → `resp.valid` 拍数（1 起计） |
| `ddrLatMax` | 复位以来的最大值 |
| `ddrGapLast` | 相邻两次核心请求 fire 的间隔拍数 |
| `ddrGapMax` | 复位以来的最大间隔；含加载器 hold 核心的那段，**不是**吞吐量 |

配置：`lxb_artix7`，100 MHz，板上无 I/D cache；两个独立场景（`boot_finish` 4 words 取指循环、`print_only`
36 words 打印后继续跑），各抓一次 `finished=1` 窗口：

| 场景 | `ddrLatLast` | `ddrLatMax` | `ddrGapLast` |
|---|---:|---:|---:|
| boot_finish | 23 | 54 | 24 |
| print_only | 23 | 54 | 24 |

**结论**：一次核心可见的 DDR 访问 = **23 拍 @100 MHz = 230 ns**（典型）；最坏 54 拍 = 540 ns（行冲突或刷新）；
请求间隔 24 拍（= 23 + 1），即这个端口一次只挂一个事务。旧模型 `15 ns + 100 ns = 12 拍 @100 MHz`
**低估约一倍**，且漏掉了 near 侧仲裁、跨时钟域和整突发适配的拍数。模型据此改为
`Delay(100 MHz, 23)`：仿真 25 MHz 下取 `⌈23 × 25/100⌉ = 6` 拍（240 ns），板卡 100 MHz 下 23 拍（230 ns）。


**校准后的仿真基线（2026-11-05，2,000,000 指令，tile，25 MHz，difftest 0 失配）**

| 量 | 值 |
|---|---:|
| `middle_cycle` | 3,846,833 |
| `ipc` | 0.5199 |
| `ibus_stall` | 1,197,057 |
| `icache_miss` / `dcache_miss` | 25,192 / 273 |
| `arm` frames / busy | 15 / 55,180 |
| `scan` frames / busy | 135 / 552,420 |
| `motion` frames / busy | 4 / 16,368 |
| 分支预测准确率 | 57.59% |

（桥接流的 `mtime` 取主机时间，逐次运行会有几十拍的差异；上表是一个样本。）

与上一版（4,004,566，带抖动）比 −157,733（−3.9%）：这是删掉抖动的差，抖动是压测源，不是物理量。与
2026-11-04 的 3,406,474 比 +440,359（+12.9%）：I2C 线 +624,968、旧 arm 表 −18,750、抖动 −158k，净 +440k。
I2C 的 `busy` 不变（三个窗口共 624,968），因为 RTL 帧不受模型抖动影响。

证据（`pulse` 可直接打开，scope `dut`；大文件已随仓库跟踪，约 85 KB 一份）：

| 文件 | 字节 | sha256 |
|---|---:|---|
| `goal/measurements/c1-ddr-latency-boot_finish-2026-11-04.vcd` | 85,982 | `ec57d8b2…3e1fd1` |
| `goal/measurements/c1-ddr-latency-print_only-2026-11-04.vcd` | 83,068 | `3d1c31df…84ddbb3` |

复核：

```
nix develop --command bash -c 'target/release/pulse_cli \
  --wave goal/measurements/c1-ddr-latency-boot_finish-2026-11-04.vcd \
  value --scope dut --at "~1" \
  --signals memory/ddrLatLast,memory/ddrLatMax,memory/ddrGapLast --radix dec'
```

## 3. 敏感度扫描（A3）

> 本节测于 I2C 帧进入 RTL 之前（2026-11-01），扫的是当时 peripheral 窗口表的 arm 行。那两行现在是
> `I2cMaster` 的帧长（写 56 / 读 66 bit × 62 拍），数字不再可改；结论（帧时间 1:1 落在关键路径、`busy` 是收益
> 上界）不变，而且现在由 RTL 保证。

固定工作负载（`robot_arm`，每 tick 10 次机械臂访问）、固定窗口（`step 4000000`，riscv32i，
25 MHz，difftest 全程），只改当时 peripheral 表里 arm 那行的两个字段：

| 配置 | 每帧拍数 | 帧数 | `residency_busy` | `middle_cycle` | busy / cycle |
|---|---:|---:|---:|---:|---:|
| 20 bit / 400 kbit/s（基线） | 1,250 | 45 | 56,250 | 6,958,945 | 0.808% |
| 56 bit / 400 kbit/s | 3,500 | 45 | 157,500 | 7,047,177 | 2.235% |
| 20 bit / 100 kbit/s | 5,000 | 45 | 225,000 | 7,107,127 | 3.166% |

**校验一：恒等式。** `busy = frames × ⌈bits × clockHz ÷ bitHz⌉` 三行全部精确成立（误差 0%）：
45 × 1,250 = 56,250；45 × 3,500 = 157,500；45 × 5,000 = 225,000。帧数在同窗口内恒为 45，
与线参数无关——工作负载不受影响，模型只改时间。

**校验二：线性叠加。** 令 `C0 = 基线 cycle − 基线 busy = 6,902,695`（不含总线等待的计算周期），
预测 `cycle = C0 + busy`：

| 配置 | 预测 cycle | 实测 cycle | 误差 |
|---|---:|---:|---:|
| 56 bit | 7,060,195 | 7,047,177 | −0.18% |
| 20 bit / 100 kbit/s | 7,127,695 | 7,107,127 | −0.29% |

误差来自窗口末帧尚未走完（`busy` 只计已发生的占用，剩余倒数不计入）。结论：**帧时间 1:1 落在
关键路径上**（单 outstanding、顺序 LSU，核心全额等待），没有与计算重叠。这同时说明 DMA 之类
"把帧时间藏起来"的 feature，其收益上界恰好就是 `busy` 本身。

**校验三：拐点修正。** 计划 §4.1 原来按 `bus / compute` 求拐点，但总线时间本身也在分母里，
正确的关系是 `占 比 = B / (C0 + B)`。稳态每 tick `C0 = 1,364,000 − 12,500 = 1,351,500`：

| 目标占比 | 每 tick 需 `B` | 每帧拍数 | 对应 `bitsPerFrame`（400 kbit/s） | 对应 `bitHz`（20 bit/帧） |
|---|---:|---:|---:|---:|
| 10% | 150,167 | 15,017 | 241 | ≈ 33.3 kHz |
| 50% | 1,351,500 | 135,150 | 2,163 | ≈ 3.7 kHz |

（旧值 219 bit / 37 kHz 与 1,092 bit / 7.3 kHz 用的是 `B/C0`，应以此表为准。）

**据此的判断题**不变、但更硬：以 20 bit 的单字节帧计，线速要落到 33 kHz 附近，总线才占 10%；
落到 4 kHz 附近才与计算持平。**"总线建模重要"这一主张必须配一个这样的场景，否则实测会一直是
计算瓶颈。**

## 4. `timeHz`（A4）

事实：

- RTL 的 `timeHz = 1 MHz`（`NzeaTileParameter.scala:76`，板卡 `Top.scala:104`）。
- 软件（remu 侧 HAL）假定 `MTIME_TICK_HZ = 10_000_000`。
- 桥接流下 CLINT 由 DPI 交给 remu 回答（主机墙钟），**RTL 的 mtime 在仿真里是死代码**：
  波形里 `clint` 无访问、mtime 读数随主机时间变化。所以这个不一致**在仿真中不可观测**，只有上板才暴露。
- 约束：`clockHz % timeHz == 0`（`ClintParameter` 的 `require`）。仿真 25 MHz、板卡 100 MHz。

三个选项：

| # | `timeHz` | 仿真 `clockHz` | 板卡 `clockHz` | 后果 |
|---|---|---|---|---|
| 1 | 10 MHz | **50 MHz** | 100 MHz | 三方一致；但改动仿真基线：16 ms 预算变 800k 拍（当前超支 1.7×，不是 3.4×），每帧变 2,500 拍，此前所有基线数字需重测 |
| 2 | 5 MHz | 25 MHz | 100 MHz | 可整除、不动基线；HAL 仍差 2×（板卡 tick 实际约 32 ms） |
| 3 | 1 MHz（现状） | 25 MHz | 100 MHz | 板卡 tick = 160 ms，软件的 16 ms 节拍差 10×；要改 remu HAL（不在本仓） |

推荐 **1**（平台自洽），但它是一次基线变更，需要先定；选项 2 是"不动基线的最小让步"。

## 5. 待定

1. ~~`bitsPerFrame` 的口径~~ 已定：`I2cMaster` 按 32 位寄存器访问拆字节，写 56 bit、读 66 bit（§1/§2）。
2. `timeHz` 选 §4 的哪个选项。
3. C2 的入口：外设窗口在合成流中不存在，需要先决定“在板上建一条真实的外设总线”（属于 D1 的
   DMA/批量候选）。在此之前，模拟器替身的硬件对照只能停留在 DDR3 那条侧面证据上。

## 6. 复现

```
# 基线（nzea 根目录）：2M 指令 + 三个 I2C 窗口的帧数与驻留
nix develop ../remu --command bash -c 'cd ../remu && just run-app robot_arm riscv32i --platform remu -- \
  --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 2000000 } and { stat print }"'

# I2C 帧长（iverilog）
nix develop --command bash -c 'just test-match nzea_device I2cMasterTest'

# DDR 延迟证据（C1）
nix develop --command bash -c 'target/release/pulse_cli \
  --wave goal/measurements/c1-ddr-latency-boot_finish-2026-11-04.vcd \
  value --scope dut --at "~1" \
  --signals memory/ddrLatLast,memory/ddrLatMax,memory/ddrGapLast --radix dec'
```
