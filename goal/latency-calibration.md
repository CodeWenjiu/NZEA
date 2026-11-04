# 延迟校准：参数出处与实测

Status: **A1–A3 完成（2026-11-01）**；A4 待决策（改值会动仿真基线）。本文是 `research-plan.md`
A 线的交付，随实现更新（不是历史记录）。

单位约定：未特别说明的"拍"= tile 时钟周期。仿真是 25 MHz（1 拍 = 40 ns），板卡是 100 MHz
（1 拍 = 10 ns）。

## 1. 两层模型（A2）

两个模型描述的是两件不同的事，不能相加：

| | 线：`Occupancy.Pipelined` 的表项 | 驻留：`Occupancy.Held` 的表项（`AccessCost.bitFrame`） |
|---|---|---|
| 物理对象 | 应答多久到（转发链的深度） | 端口被占多久（一帧在线上的时间） |
| 是否压吞吐 | 否（背靠背请求仍每拍一个） | 是（一次一帧，帧长同时是间隔下限） |
| 是否可叠加 | — | 不可：帧会捕获内层的应答，内层延迟被吞在帧里 |
| 影响范围 | 桥的整个窗口 | 只有表内地址段 |

### 实测证据

一次 3,000,000 指令的运行，其中第 2,000,000 后开着波形跑 1,000,000 指令（窗口 1,695,398 拍）：

```
just run-app robot_arm riscv32i --platform remu -- --platform nzea --sim-opt target=tile \
  --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 2000000 } and { func trace wave-form on } and { step 1000000 } \
              and { func trace wave-form off } and { stat stat_peripheral_residency_busy }"
```

桥端口（`TOP.NzeaTile.peripheral`）83 次访问，从请求握手到响应握手的距离：

| 距离 | 次数 | 归属 |
|---|---:|---|
| ≤ 4 拍 | 48 | 线（latency 4，抖动未中签） |
| ≤ 8 拍 | 66 | 线（实测上限 8 拍；模型上界是 4 + 两侧抖动 = 12） |
| 9 – 1249 拍 | **0** | 空档 |
| 恰 1250 拍 | **17** | 帧驻留（= 2,000,000 指令后的表内访问） |

同一窗口内看驻留模块的内层（`TOP.NzeaTile.peripheral.residency` 的 `out` 侧，即线 + DPI）：

| 距离 | 次数 |
|---|---:|
| ≤ 4 拍 | 61 |
| ≤ 6 拍 | 64 |
| ≤ 8 拍 | 83（全部） |

结论：**内层全部 ≤8 拍，外层却出现 17 个恰好 1250 的样本，且不存在 1254–1258 的样本**——帧没有把
线的延迟加在自己身上，而是把内层的应答捕获后按帧长交付。这就是"双重"的正确读法：两层是两个
互斥的可观测（表内看驻留，表外看线），不是两段延迟首尾相接。

帧命中数（`inRange && in_req fire`）也独立统计到 17，与上面 17 个长样本一致，交叉验证成立。

### 声明方式：速率 vs 结构（设计规则）

两层的参数形状不同，不是历史遗留，而是因为物理量不同。判据是**这个延迟在物理上是"一段速率"还是
"几个触发器"**：

| | 声明 | 随时钟换算 | 物理时间 |
|---|---|---|---|
| 缺点是**周期/速率** | `AccessCost.bitFrame(bitHz, bits)`、`Clint` 的 `timeHz` | 是：`⌈bits × clockHz ÷ bitHz⌉`、`clockHz ÷ timeHz` | 不变（一帧恒为 50 µs） |
| 缺点是**同域** | `AccessCost.SameDomain(cycles)` | 否：就是总线拍数 | 随总线时钟变（4 拍 = 160 ns @25 MHz、40 ns @100 MHz） |

规则：

1. **物理量是设备自己的周期或比特流** —— 一律声明频率/速率（`Periods`/`bitFrame`），由实例化处的时钟
   推导拍数。驻留与 `mtime` 已经是这个形状。
2. **设备与总线同域** —— 声明拍数（`SameDomain`），**不要**用频率比去推。一个三拍应答的寄存器在任何频率下
   都是 3 拍；写成"设备 100 MHz、总线 1 GHz → 10 拍"会把同一时钟域里的 3 拍说成 10 拍。
3. 两者的正确组合是 `latency(busHz) = ⌈deviceCycles × busHz ÷ deviceHz⌉ + 同域拍数`：前项是设备周期、
   后项是同一时钟下的计数。
4. 因此 `SameDomain` 的数值**不会随时钟缩放**：仿真 25 MHz 与板卡 100 MHz 下同一个 4 拍是不同的物理时间。
   这不是 bug，是"它与总线同域"的声明；真正的物理延迟（如 DDR3 的 CL 6 × 2.5 ns = 15 ns）不能用它表达，
   必须按第 1 条写成频率。

这条规则现在由类型强制（2026-11-01）：`TimingEntry` 的代价是一个 `AccessCost`——`SameDomain(cycles)` 或
`Periods(deviceHz, cycles)`，前者不随时钟缩放，后者按 `⌈cycles × busHz ÷ deviceHz⌉` 换算。**表里的数就是
主设备看到的拍数**：模型只加表里声明的阶段，不加自己的固定开销；1 拍是协议下限（应答必须能保持到主设备
取走），无表（`None`）的端口则直接是模拟器的一级应答。`Sum` 把不同来源的部分先精确相加再舍入一次。
仿真/板卡的两种时钟不变性、`Sum` 的换算与表的合法性都由 `LiteBusTest` 钉住。

## 2. 参数出处表（A1）

分类：**[S]** 标准/手册 · **[D]** 由帧格式推导 · **[R]** 由本仓 RTL 自身的应答距离标定 · **[H]** 由板级硬件事实推导
· **[N]** 无物理对应（该值只存在于仿真） · **[O]** 尚无出处。

**校准方法（2026-11-01 定）**：端口时序表的职责是让桥接流的主设备看到与合成流相同的总线，所以它的值
应当等于**合成流里该设备 RTL 自己的请求→应答距离**，而不是凭感觉给一个“慢”数。据此逐个查 RTL：

| 设备 | RTL 的应答距离 | 出处 | 校准后 | 备注 |
|---|---:|---|---|---|
| CLINT | 1 拍 | `Clint.scala`：`req.ready := !busy` / `resp.valid := busy` | 无表（`None`） | 端口不建模型，模拟器的一级应答就是这一拍 |
| UART | 3 拍 | `Uart.scala:193`：`resp.valid := busCycle === 3` | `SameDomain(3)` | 从 8 降下来 |
| Finisher | 1 拍 | 单寄存器 `busy`（原三拍 `busCycle` 状态机已压掉，与 CLINT 同形） | 无表（`None`） | 端口不建模型 |
| 片上 RAM | 3 拍 | `RamFabricSlave.scala:116`（同一 `busCycle` 模式） | 不适用 | 板上这个窗口被 512 MiB 的 DDR3 外部窗口覆盖（`Top.scala` 的 `Window = "ram"`），所以本项代表的是 DDR3，见下 |

抖动（`jitterEvery`/`jitterSpan`）**不参与校准**：它是压测主设备 hold/retry 路径的随机源（固定种子），不是
物理量。所以波形上看到的单次延迟会略大于上表的拍数（每侧平均 +0.6 拍、最坏 +4）。

| 参数 | 值 | 位置 | 物理含义 | 出处 | 状态 |
|---|---|---|---|---|---|
| `bitFrame.bitHz`（机械臂） | 400,000 | `DeviceTimings.scala` `DeviceTimings.arm` | 线的比特率 | **[S]** I2C Fast-mode 400 kbit/s（NXP UM10204 §3.1.3）；本仓 `doc/tms320f28p650dk.pdf` §1 特性同值 | 已校准 |
| `bitFrame.bits`（机械臂） | 20 | 同上 | 单字节帧占线总位数 | **[D]** START 1 + 地址 7 + R/W 1 + ACK 1 + 数据 8 + ACK 1 + STOP 1 = 20 | 已校准（口径 20） |
| （32 位寄存器口径） | 56 / 66 | — | 寄存器指针 + 4 数据字节（读再加重复起始） | **[D]** 10 + 9 + 4×9 + 1 = 56；读 ≈ 66 | 待定：见 §5 |
| `DeviceTimings.ram` | `Periods(100 MHz, 23)` | `DeviceTimings.scala` | 核心可见的整条 DDR 通路 | **[M]** 板上实测（C1，2026-11-04）：23 拍 × 10 ns = 230 ns 典型，54 拍最坏 | 已校准 |
| `DeviceTimings.uart` | `SameDomain(3)` | 同上 | 寄存器访问 | **[R]** RTL 3 拍 | 已校准 |
| finisher | 无表（`None`） | `SifiveTestFinisher.scala` | 退出钩子 | **[R]** RTL 1 拍（单寄存器）；端口不建模型 | 已校准（逐拍一致） |
| CLINT | 无表（`None`） | `Clint.scala` | 寄存器访问 | **[R]** RTL 1 拍；端口不建模型，直接是模拟器的一级应答 | 已校准（逐拍一致） |
| `DeviceTimings.peripheral`（桥） | `Periods(25 MHz, 1)` | 同上 | 次级总线一次访问 | **[R]** 与 `PlatformDevices.PeripheralDeviceHz/Cycles` 同源，硬件 `PeripheralBus` 按同两个数分频 | 已校准（25 MHz 下 1 拍） |
| `clockHz`（仿真） | 25 MHz | `NzeaTileParameter.scala:75` | tile 时钟 | **[P]** 基线参数 | 已明确 |
| `clockHz`（板卡） | 100 MHz | `nzea_fpga/src/Top.scala:103` ← `BootDdr.scala:82` | tile 时钟 | **[H]** `ClockOut(100.0, 0.0)` | 已明确 |
| `timeHz` | 1 MHz | `NzeaTileParameter.scala:76`、`Top.scala:104` | mtime 计数率 | **[O]** 与软件 10 MHz 假定冲突 | OPEN → §4 |

**三条结论：**

1. 三个 `DeviceTimings` 表（外加 CLINT/finisher 两个无表端口）**全部只影响仿真**（消费点只有
   `HasDpiBus.answerBus`，它在 `flow.bridges` 才走）。其中 UART 是**按合成流 RTL 标定**的；CLINT 与 finisher
   无表，直接是模拟器的一级应答，与各自 RTL 的单寄存器逐拍一致；桥没有硬件可对；RAM 对应的是 DDR3+MIG，
   控制器延迟仍无源。
2. 板级**没有外设窗口**：`PlatformDevices` 的合成流清单只有 `ram`/`uart16550`/`finisher`/`clint`，没有
   `peripheral`——arm/scan/motion 在板上不存在，两层模型目前是纯仿真对象——**C1 被这一缺口阻塞**，不是被埋点阻塞。
3. DDR3 不再是“只有下界有源”：整条通路已在板上实测（C1，230 ns 典型），旧模型 `15 + 100 ns` 低估约一倍；
   详情见 §2 末尾的 C1 小节。

测量于 2026-11-01，早于模型下限从 2 拍降到 1 拍：下表"桥 2 拍"那一列现在是 1 拍，CLINT 也不再建模型，
两项都只快了一拍，比例结论不变。

| 量 | RAM 20 拍 / 桥 4 拍 | RAM 3 拍 / 桥 2 拍 |
|---|---:|---:|
| `middle_cycle` | 3,539,903 | **3,047,996**（−13.9%） |
| `ipc` | 0.5650 | **0.6562**（+16.1%） |
| `ibus_stall` | 893,527 | **401,223**（−55.1%） |
| `icache_miss` | 25,135 | 25,142（不变） |
| `residency_frames` / `busy` | 15 / 18,750 | 15 / 18,750 |

两件事：

1. **寄存器文件（UART/CLINT/finisher）的延迟不是成本所在** —— 上一轮把 8 改成 3/2 时总账只动了 −34 拍。
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
请求间隔 24 拍（= 23 + 1），即这个端口一次只挂一个事务。旧模型 `Sum(15 ns + 100 ns) = 12 拍 @100 MHz`
**低估约一倍**，且漏掉了 near 侧仲裁、跨时钟域和整突发适配的拍数。模型据此改为
`Periods(100 MHz, 23)`：仿真 25 MHz 下取 `⌈23 × 25/100⌉ = 6` 拍（240 ns），板卡 100 MHz 下 23 拍（230 ns）。

模型仍把这行标成 `Pipelined`：对当前单 outstanding 的核没有差别（间隔就是延迟 + 1）；若以后接 DMA 或
多 outstanding 主设备，需要 `SingleSlot`，否则模型会比硬件乐观。

**校准后的仿真基线（2026-11-04，2,000,000 指令，tile，25 MHz，difftest 0 失配）**

| 量 | 值 |
|---|---:|
| `middle_cycle` | 3,131,463 |
| `ipc` | 0.6387 |
| `ibus_stall` | 484,600 |
| `icache_miss` / `dcache_miss` | 25,116 / 273 |
| `residency_frames` / `busy` | 15 / 18,750 |
| 分支预测准确率 | 57.60% |

与 2026-11-01 的 3,047,996 比，总量 +2.7%。这期间同时改了 RAM（3→6 拍）、桥（2→1 拍）和 CLINT/finisher
（改为无表），不能把差值单独归给 RAM；同向证据是 25,542 次 cache miss × 3 拍 ≈ 76.6k，与 +83.5k 同量级。

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

固定工作负载（`robot_arm`，每 tick 10 次机械臂访问）、固定窗口（`step 4000000`，riscv32i，
25 MHz，difftest 全程），只改 `DeviceTimings.peripheral` 里 arm 那行的两个字段：

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

1. `bitsPerFrame` 的口径：20（单字节帧，当前值）还是 56/66（32 位寄存器访问的实际线上位数）。
   后者更接近 HAL 的真实访问宽度，会让总线上限从 12,500 拍/tick 变成 35,000–36,000 拍/tick。
2. `timeHz` 选 §4 的哪个选项。
3. C1/C2 的入口：外设窗口在合成流中不存在，需要先决定"在板上建一条真实的外设总线"（属于 D1 的
   DMA/批量候选）。在此之前，两层模型的硬件对照只能停留在 DDR3 那条侧面证据上。

## 6. 复现

```
# A2 波形窗口（nzea 根目录）
nix develop ../remu --command bash -c 'cd ../remu && just run-app robot_arm riscv32i --platform remu -- \
  --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 2000000 } and { func trace wave-form on } and { step 1000000 } \
              and { func trace wave-form off } and { stat stat_peripheral_residency_busy }"'

# A2 测量
nix develop --command bash -c 'cargo build --release --quiet -p pulse_cli && target/release/pulse_cli \
  property --wave ../remu/target/trace.fst --scope TOP.NzeaTile.peripheral \
  --eval "(bus_req_valid && bus_req_ready) --1250 (bus_resp_valid && bus_resp_ready)" --count --json'

# A3 扫描：改 arm 那行的 AccessCost.bitFrame，然后
nix develop ../remu --command bash -c 'cd ../remu && just run-app robot_arm riscv32i --platform remu -- \
  --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 4000000 } and { stat stat_peripheral_residency_frames } \
              and { stat stat_peripheral_residency_busy } and { stat stat_core_middle_cycle }"'
```
