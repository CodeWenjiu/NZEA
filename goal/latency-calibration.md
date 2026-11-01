# 延迟校准：参数出处与实测

Status: **A1–A3 完成（2026-11-01）**；A4 待决策（改值会动仿真基线）。本文是 `research-plan.md`
A 线的交付，随实现更新（不是历史记录）。

单位约定：未特别说明的"拍"= tile 时钟周期。仿真是 25 MHz（1 拍 = 40 ns），板卡是 100 MHz
（1 拍 = 10 ns）。

## 1. 两层模型（A2）

两个模型描述的是两件不同的事，不能相加：

| | 线 `DeviceTiming(latency, jitterEvery, jitterSpan)` | 驻留 `FrameTiming(bitHz, bitsPerFrame)` / `FramedRange` |
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
| 缺点是**周期/速率** | `FrameTiming(bitHz, bitsPerFrame)`、`Clint` 的 `timeHz` | 是：`⌈bits × clockHz ÷ bitHz⌉`、`clockHz ÷ timeHz` | 不变（一帧恒为 50 µs） |
| 缺点是**同域** | `DeviceTiming(SameDomain(cycles), …)` | 否：就是总线拍数 | 随总线时钟变（4 拍 = 160 ns @25 MHz、40 ns @100 MHz） |

规则：

1. **物理量是设备自己的周期或比特流** —— 一律声明频率/速率（`Periods`/`FrameTiming`），由实例化处的时钟
   推导拍数。驻留与 `mtime` 已经是这个形状。
2. **设备与总线同域** —— 声明拍数（`SameDomain`），**不要**用频率比去推。一个三拍应答的寄存器在任何频率下
   都是 3 拍；写成"设备 100 MHz、总线 1 GHz → 10 拍"会把同一时钟域里的 3 拍说成 10 拍。
3. 两者的正确组合是 `latency(busHz) = ⌈deviceCycles × busHz ÷ deviceHz⌉ + 同域拍数`：前项是设备周期、
   后项是同一时钟下的计数。
4. 因此 `SameDomain` 的数值**不会随时钟缩放**：仿真 25 MHz 与板卡 100 MHz 下同一个 4 拍是不同的物理时间。
   这不是 bug，是"它与总线同域"的声明；真正的物理延迟（如 DDR3 的 CL 6 × 2.5 ns = 15 ns）不能用它表达，
   必须按第 1 条写成频率。

这条规则现在由类型强制（2026-11-01）：`DeviceTiming` 的代价是一个 `AccessCost`——`SameDomain(cycles)` 或
`Periods(deviceHz, cycles)`，前者不随时钟缩放，后者按 `⌈cycles × busHz ÷ deviceHz⌉` 换算，低于模型自身
的两级寄存器时钳到 2。仿真/板卡的两种时钟不变性由 `LiteBusResidencyTest`（帧）与 `LiteBusTest`（线）
各自钉住。

## 2. 参数出处表（A1）

分类：**[S]** 标准/手册 · **[D]** 由帧格式推导 · **[R]** 由本仓 RTL 自身的应答距离标定 · **[H]** 由板级硬件事实推导
· **[N]** 无物理对应（该值只存在于仿真） · **[O]** 尚无出处。

**校准方法（2026-11-01 定）**：`DeviceTiming` 的职责是让桥接流的主设备看到与合成流相同的总线，所以它的值
应当等于**合成流里该设备 RTL 自己的请求→应答距离**，而不是凭感觉给一个“慢”数。据此逐个查 RTL：

| 设备 | RTL 的应答距离 | 出处 | 校准后 | 备注 |
|---|---:|---|---|---|
| CLINT | 1 拍 | `Clint.scala`：`req.ready := !busy` / `resp.valid := busy` | `SameDomain(2)` | 模型下限是 2（转发自带应答寄存器 + 输出寄存器），1 拍表达不了，取下限 |
| UART | 3 拍 | `Uart.scala:193`：`resp.valid := busCycle === 3` | `SameDomain(3)` | 从 8 降下来 |
| Finisher | 3 拍 | `SifiveTestFinisher.scala:89`（同一 `busCycle` 模式） | `SameDomain(3)` | 从 4 降下来 |
| 片上 RAM | 3 拍 | `RamFabricSlave.scala:116`（同一 `busCycle` 模式） | 不适用 | 板上这个窗口被 512 MiB 的 DDR3 外部窗口覆盖（`Top.scala` 的 `Window = "ram"`），所以本项代表的是 DDR3，见下 |

抖动（`jitterEvery`/`jitterSpan`）**不参与校准**：它是压测主设备 hold/retry 路径的随机源（固定种子），不是
物理量。所以波形上看到的单次延迟会略大于上表的拍数（每侧平均 +0.6 拍、最坏 +4）。

| 参数 | 值 | 位置 | 物理含义 | 出处 | 状态 |
|---|---|---|---|---|---|
| `FrameTiming.bitHz`（机械臂） | 400,000 | `DeviceTimings.scala` `DeviceFrames` | 线的比特率 | **[S]** I2C Fast-mode 400 kbit/s（NXP UM10204 §3.1.3）；本仓 `doc/tms320f28p650dk.pdf` §1 特性同值 | 已校准 |
| `FrameTiming.bitsPerFrame`（机械臂） | 20 | 同上 | 单字节帧占线总位数 | **[D]** START 1 + 地址 7 + R/W 1 + ACK 1 + 数据 8 + ACK 1 + STOP 1 = 20 | 已校准（口径 20） |
| （32 位寄存器口径） | 56 / 66 | — | 寄存器指针 + 4 数据字节（读再加重复起始） | **[D]** 10 + 9 + 4×9 + 1 = 56；读 ≈ 66 | 待定：见 §5 |
| `DeviceTiming.ram` | `SameDomain(20)` | `DeviceTimings.scala` | DDR3 + MIG 的访问延迟 | **[O]** 只有下界有源：DRAM 本体 CL 6 × 2.5 ns = 15 ns；README 的压测一轮 ≈ 230 ns；MIG 控制器延迟无源 | **未校准**，待 C1 |
| `DeviceTiming.uart` | `SameDomain(3)` | 同上 | 寄存器访问 | **[R]** RTL 3 拍 | 已校准 |
| `DeviceTiming.finisher` | `SameDomain(3)` | 同上 | 退出钩子 | **[R]** RTL 3 拍 | 已校准 |
| `DeviceTiming.clint` | `SameDomain(2)` | 同上 | 寄存器访问 | **[R]** RTL 1 拍，取模型下限 2 | 已校准（误差 +1 拍） |
| `DeviceTiming.peripheral`（桥） | `SameDomain(4)` | 同上 | 次级总线前向 | **[N]** 合成流没有外设窗口（`PlatformDevices.devices(RtlFlow.Yosys)` 不含 `peripheral`），无 RTL 可对照 | **未校准** |
| `clockHz`（仿真） | 25 MHz | `NzeaTileParameter.scala:75` | tile 时钟 | **[P]** 基线参数 | 已明确 |
| `clockHz`（板卡） | 100 MHz | `nzea_fpga/src/Top.scala:103` ← `BootDdr.scala:82` | tile 时钟 | **[H]** `ClockOut(100.0, 0.0)` | 已明确 |
| `timeHz` | 1 MHz | `NzeaTileParameter.scala:76`、`Top.scala:104` | mtime 计数率 | **[O]** 与软件 10 MHz 假定冲突 | OPEN → §4 |

**三条结论：**

1. `DeviceTimings` 的五个值**全部只影响仿真**（消费点只有 `HasDpiBus.bridgeAnswer`，它在 `flow.bridges` 才走）。
   其中三个现在是**按合成流 RTL 标定**的（CLINT/UART/finisher）；桥没有硬件可对；RAM 对应的是 DDR3+MIG，
   控制器延迟仍无源。
2. 板级**没有外设窗口**：`PlatformDevices` 的合成流清单只有 `ram`/`uart16550`/`finisher`/`clint`，没有
   `peripheral`——arm/scan/motion 在板上不存在，两层模型目前是纯仿真对象——**C1 被这一缺口阻塞**，不是被埋点阻塞。
3. 唯一同时有外部出处和可上板对照的量是 DDR3：本体 15 ns（CL 6 × 2.5 ns），控制器余量待 C1。

**校准的实测影响（2026-11-01，`step 2000000`，robot_arm）**：`cycle` 3,539,937 → 3,539,903（−34，≈ −0.001%），
`frames`/`busy` 不变（15 / 18,750），difftest 无失配。即：**寄存器文件的延迟不是这个工作负载的成本所在**，
把它校准之后总账几乎不动——成本在帧驻留和计算侧。

## 3. 敏感度扫描（A3）

固定工作负载（`robot_arm`，每 tick 10 次机械臂访问）、固定窗口（`step 4000000`，riscv32i，
25 MHz，difftest 全程），只改 `DeviceFrames` 的两个字段：

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

# A3 扫描：改 DeviceFrames 的 FrameTiming，然后
nix develop ../remu --command bash -c 'cd ../remu && just run-app robot_arm riscv32i --platform remu -- \
  --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 4000000 } and { stat stat_peripheral_residency_frames } \
              and { stat stat_peripheral_residency_busy } and { stat stat_core_middle_cycle }"'
```
