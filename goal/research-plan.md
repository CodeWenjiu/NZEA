# 研究计划：延迟校准 → 对标 → 创新

Status: **草案（2026-10-30）** — 由与导师的讨论整理。三个开放问题（§5）未定之前，任务 A1–A3 可执行，B1–B2 可准备，C1–D1 阻塞。

## 0. 一句话

这个研究要做的是：**先让仿真里的延迟数字有出处且可复现，再用真板/真机数据交叉验证，然后在可信平台上与现有方案比速度/功耗/面积，最后做功能扩展与 CPU 设计迭代。**

顺序不可颠倒——数字不可信时，"比较"和"创新"都无处落脚。

## 1. 四条主线

| # | 主线 | 交付物 | 阻塞关系 |
|---|---|---|---|
| A | **延迟校准** | 每个延迟参数带出处；两层模型的语义分清 | 无（可立即开始） |
| B | **调研与对标** | 相关工作差异表 + baseline（速度/功耗/面积/集成度，含 CPU 与加速器） | 需 A 的自有数字 |
| C | **上板验证** | FPGA 真实运行数据 → 回填 A；FPGA→PC 数据通路 | 需 A 的埋点与 C1 |
| D | **创新** | feature 扩展 + CPU 设计取舍的量化对比 | 需 A–C |

## 2. 任务

### A 延迟校准

**A1 — 建立"延迟参数出处表"。**
位置：`nzea_device/src/DeviceTimings.scala`（`DeviceTimings` §13–24，`DeviceFrames` §37–42）、`nzea_rtl/src/bus/LiteBusTiming.scala:24`（`DeviceTiming` 三字段）、`nzea_rtl/src/bus/LiteBusResidency.scala:18`（`FrameTiming` 两字段）。
做法：§6 的模板逐行填；每条来源标类型（手册条款 / 实测命令+日期 / 推导公式），无出处者标 `OPEN` 并写"要什么证据才能定"。
验收：§6 表内 `OPEN` 行为零，或每一 `OPEN` 行都写明获取该证据的具体途径（哪份手册哪节、或哪条实测命令）。

**A2 — 澄清"双重校准"的两层语义，并防止串味。**
位置：`DeviceTiming`（线：应答多久到，不压吞吐）vs `FrameTiming`/`FramedRange`（驻留：端口占多久，压吞吐）。
做法：写一页对照（字段/单位/物理对象/可否叠加），并用一次波形实测坐实二者不可相加。
验收：同一波形上能同时指出"线延迟 ≤ 8 拍"与"帧驻留恰为帧长"两类样本（2026-10-30 已观测到：桥端口 914 次访问中 799 次 ≤8 拍、115 次恰 1250 拍，中间空档），把这组证据记入本节。

**A3 — 总线敏感度扫描（决定研究该主张什么）。**
位置：`DeviceFrames.frames`（`bitsPerFrame`/`bitHz`）、`DeviceTimings.peripheral`。
做法：固定工作负载（`robot_arm`，每 tick 10 次机械臂访问），扫 `bitHz ∈ {100k, 400k, 1M}` × `bitsPerFrame ∈ {20, 56, 66}`，记录 `stat_peripheral_residency_busy / stat_core_middle_cycle`。
验收：一张曲线 + §4 的解析拐点被至少一个实测点验证（误差 ≤5%）。

**A4 — `timeHz` 与软件假定的不一致（阻塞上板）。**
位置：`nzea_tile/src/NzeaTileParameter.scala:76`（`timeHz = 1 MHz`）、`nzea_fpga/src/Top.scala:104`（同上）；软件侧假定 10 MHz（`remu_app/hal/remu_hal_embedded/src/time.rs` 的 `MTIME_TICK_HZ`）。
做法：定一个值并说明理由；若改 RTL，须保证 `clockHz % timeHz == 0`（`ClintParameter` 的 `require`）。
验收：板卡构建下 `mtime` 每毫秒增量的实测值与软件假定一致（用 ILA 或串口打点）。

### B 调研与对标

**B1 — 相关工作差异表（防撞车）。**
做法：按"总线/片外延迟建模""机器人控制 SoC""RTL 性能建模与校准"三类各列 3–5 篇，每篇一行：方法、评价指标、是否用真实器件校准、与本工作的差异。
验收：表内每篇都能回答"我们与它的差异是否足以构成创新点"；若某篇已覆盖本工作全部主张，写进 §5 作为方向调整建议。

**B2 — baseline 定义（速度/功耗/面积/集成度）。**
候选：`doc/` 已收集的 TI C2000 F28P65x 系列（200 MHz C28x+CLA，600 MIPS，FPU64/TMU，1.28 MB Flash，2×I2C 400 kbit/s，2×6ch DMA，6×CLB）——真实的伺服控制主流器件，三个维度都有公开数字。
做法：列 2–3 个 baseline（至少 1 个 CPU-only、1 个带加速器），填表：工艺、频率、MIPS/DMIPS、功耗、面积、集成外设。
验收：每个 baseline 的每个格子有出处或标 `N/A + 原因`；并写明"我们的 RTL 目前无法给出功耗/面积，需综合报告"这一缺口如何补（`just sta` 已有面积流程）。

### C 上板

**C1 — 用 ILA 测一次真实的"请求→应答→下一次请求"。**
位置：板级流程见 `.agents/skills/fpga-flow/SKILL.md`。
做法：抓 `peripheral` 端口的一组握手，量 (a) 单次访问的响应延迟，(b) 连续两次访问的间隔。
验收：得到的两个量与仿真同量对比，**先验形状，再验绝对值**（形状不符说明模型结构错，绝对值不符只需改参数）。

**C2 — FPGA→PC 数据通路 + 真实机械臂数据校准。**
做法：板卡把设备访问时间戳/计数经 UART 或 DDR 缓冲送到 PC，与 PC 侧的机械臂时序对齐。
验收：一次连续运行中，PC 侧记录与板卡计数的帧数一致（±1 帧），并给出"真实关节总线一次访问耗时"的实测区间。
触发条件：C1 完成后开始；若 C1 显示模型形状不符，先修模型再谈 C2。

### D 创新（阻塞，见 §5）

**D1 — feature 扩展候选**（等 §5 第 3 问定题后再拆任务）：DMA/批量传输、硬件浮点或三角函数单元、可编程逻辑（类 CLB）、多核/加速器挂载。
**D2 — CPU 设计对比**：在同一工作负载下比较配置差异（如 `--isa riscv32i/riscv32im`、cache 配置、BP 策略），产出"设计取舍 → 性能"的对应关系。

## 3. 现状（已具备，勿重复建设）

| 能力 | 位置 |
|---|---|
| 可复现 RTL 仿真闭环（difftest + stat + 波形） | `.agents/skills/remu/SKILL.md`、`.agents/skills/pulse/SKILL.md` |
| 两层延迟模型 + 自校验计数器（`frames × 帧长 = busy`） | `nzea_rtl/src/bus/LiteBusResidency.scala`、`nzea_device/src/DeviceTimings.scala` |
| 机械臂工作负载（16 ms 节拍，IK 16 迭代，64 射线） | `../remu/remu_app/bins/robot_arm/src/main.rs:51,64` |
| 板级流程（Vivado/ILA 抓取） | `.agents/skills/fpga-flow/SKILL.md` |
| 真实器件参照资料 | `doc/`（TI C2000 数据手册 + CLA/CLB 应用笔记，未入库） |

**已测数字（2026-10-30，tile，25 MHz，difftest 全程，0 失配）**

| 量 | riscv32i | riscv32im |
|---|---:|---:|
| 每 tick 指令 | 800,000 | 166,700 |
| 每 tick 周期 | 1,364,000 | 630,000 |
| 每 tick 片外线驻留（10 × 1250） | 12,500（0.92%） | 12,500（2.0%） |
| IPC | 0.585 | 0.262 |
| 分支预测准确率 | 56.6% | 28.5% |
| icache miss / tick | 8,713 | 6,562 |

复现命令（nzea 根目录）：

```
nix develop ../remu --command bash -c 'cd ../remu && just run-app robot_arm riscv32i --platform remu -- \
  --platform nzea --sim-opt target=tile --sim-opt watchdog=3x30 --batch --difftest remu \
  --startup "{ step 2000000 } and { stat print }"'
```

## 4. 关键判断（会改变计划的两个事实）

**4.1 现有配置下总线不是瓶颈，校准的边际收益取决于主张。**
每 tick 计算 1,364,000 拍 vs 总线 12,500 拍 ≈ **109 : 1**。由此解析出"总线开始显著"的门槛（25 MHz，每 tick 10 次访问）：

| 目标占比 | 每帧周期 | 对应 `bitsPerFrame`（400 kbit/s） | 对应 `bitHz`（20 bit/帧） |
|---|---:|---:|---:|
| 10% | 13,640 | 219 | ≈ 37 kHz |
| 50% | 68,200 | 1,092 | ≈ 7.3 kHz |

即：要么线速再慢一个数量级，要么每帧位数多一个数量级，总线才会成为一等项。**所以论文若主张"总线建模重要"，必须选一个总线占比显著的场景（更慢的线 / 更多设备 / 无 DMA 的阻塞访问）；若主张"计算瓶颈、该做加速器"，则校准只需证明总线不是瓶颈。** 两种主张对校准精度的要求差一个量级。

**4.2 上板数字不能由仿真缩放得到。**
板卡构建的 cache 是关掉的（`nzea_fpga/src/Top.scala:108-109`，`icache = None`/`dcache = None`），时钟 100 MHz（`nzea_fpga/src/logic/BootDdr.scala:82`）。计算周期不会随频率缩小，而缺 cache 会显著增大——所以板上的"计算:总线"必须重测，不能按频率比例推算。

## 5. 开放问题（未定，不写决定）

1. **"防撞车"还是"防被复现"**：讨论中的"防止复现"指"避免与已有工作重合"（则产出 B1 差异表），还是"防止他人复现我们的平台"（则要写可复现性说明）？
2. **"双重校准"**：指两层延迟（线 + 帧驻留，§A2），还是"仿真校准 + 真机校准"两条路径？两者都成立时，A2 的对照页需要同时覆盖两个维度。
3. **feature 题目**：D1 的四个候选中选哪个？这决定 B2 的 baseline 选型（例如选"硬件三角函数/浮点"就该对标 CLA/TMU；选"DMA/批量"就该对标 2×6ch DMA；选"可编程逻辑"就该对标 CLB）。
4. **baseline 家族**：C2000 是否作为主对标？若主对标是 RISC-V 应用处理器（如带向量扩展的核），B2 全表需重做。

## 6. 延迟参数出处表（A1 的交付模板）

单位：tile 时钟周期。`OPEN` = 当前无出处，必须补。

| 参数 | 当前值 | 位置 | 物理含义 | 来源 | 状态 |
|---|---|---|---|---|---|
| `DeviceTiming.latency`（桥） | 4 | `DeviceTimings.scala:24` | 请求被接受到响应给出 | — | OPEN |
| `DeviceTiming.jitterEvery/Span`（桥） | 4 / 4 | 同上 | 平均 1/4 次握手延后 0–4 拍 | — | OPEN |
| `DeviceTiming.latency`（RAM） | 20 | `DeviceTimings.scala:13` | 片外存储访问延迟 | — | OPEN |
| `DeviceTiming.jitter`（RAM） | 8 / 8 | 同上 | 行冲突类抖动 | — | OPEN |
| `DeviceTiming`（UART / CLINT / finisher） | 8 / 8 / 4 | `DeviceTimings.scala:16-19` | 慢速寄存器访问 | — | OPEN |
| `FrameTiming.bitHz`（机械臂） | 400,000 | `DeviceTimings.scala:40` | 线的比特率 | I2C 标准速率（手册待引） | 待落出处 |
| `FrameTiming.bitsPerFrame`（机械臂） | 20 | `DeviceTimings.scala:40` | 单字节帧的线上总位数 | 由帧格式推导（start+addr+RW+ACK+data+ACK+stop） | OPEN：32 位寄存器访问的实际位数（56）待定口径 |
| `clockHz`（仿真） | 25 MHz | `NzeaTileParameter.scala:75` | tile 时钟 | 平台事实 | 已明确 |
| `clockHz`（板卡） | 100 MHz | `nzea_fpga/src/Top.scala:103` | 同上 | `BootDdr.ClockGrid` | 已明确 |
| `timeHz` | 1 MHz | `NzeaTileParameter.scala:76`、`Top.scala:104` | CLINT 计数率 | — | OPEN（与软件 10 MHz 假定冲突，见 A4） |
