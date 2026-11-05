# 研究计划：延迟校准 → 对标 → 创新

Status: **草案（2026-10-30）**；2026-11-01 更新：A1–A3 已完成（交付见 `latency-calibration.md`），
A4 待决策；2026-11-04：C1 的内存部分已完成（板上实测 DDR 230 ns，模型已校准）；2026-11-05：UART 与
I2C（arm/scan/motion）的内部逻辑进入 RTL，总线占比重测（§4.1 修正）。C2 仍被“合成流没有外设窗口”阻塞。
三个开放问题（§5）未定之前，B1–B2 可准备，C2–D1 阻塞。

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

**A1 — 建立"延迟参数出处表"。** ✅ 完成（2026-11-01；2026-11-05 随实现更新）
交付：`latency-calibration.md` §2。结论：延迟都由设备自己声明（`HasBridgeDelay` 的默认 `None`，RAM 与 display 窗口 override），
代表的都是模拟器独有的硬件（板卡 DDR3、remu 的帧缓冲）；UART 与 I2C 的内部逻辑已进入 RTL（两种流同一份），
CLINT/finisher 无延迟；DDR3 的 230 ns 已上板实测。

**A2 — 澄清延迟归谁写（原“双重校准”）。** ✅ 完成（2026-11-01；2026-11-05 随实现更新）
交付：`latency-calibration.md` §1。规则：**延迟由产生它的硬件自己声明**——设备/线上的状态机进 RTL（UART 发送线
2,180 拍/字节；I2C 写 56 / 读 66 bit），只有模拟器独有的硬件才由设备声明延迟（RAM 的 DDR 路径、display 窗口）。
推导）。2M 指令的实测：三个 I2C 窗口 `busy` 恰好是帧长的整数倍（写 56 × 62 = 3,472；读 66 × 62 = 4,092），
计数器数的是 RTL 的帧。

**A3 — 总线敏感度扫描（决定研究该主张什么）。** ✅ 完成（2026-11-01，扫的是旧模型）
交付：`latency-calibration.md` §3。三个配置（20b/400k、56b/400k、20b/100k）实测 `busy = frames ×
帧长` 误差 0%，且 `cycle = C0 + busy` 误差 <0.3%——帧时间 1:1 在关键路径上。那两行现在由 I2C RTL 取代
（56/66 bit），结论不变，但当前的实测占比见 §4.1。

**A4 — `timeHz` 与软件假定的不一致（阻塞上板）。** ⏸ 待决策（证据已齐）
交付：`latency-calibration.md` §4。仿真不可观测（桥接流下 mtime 是死代码），只有上板暴露；三个选项
与代价已列，推荐 `timeHz = 10 MHz` + 仿真 50 MHz，但会动基线。

### B 调研与对标

**B1 — 相关工作差异表（防撞车）。** 🚧 初版（2026-11-05，元数据层）
交付：`related-work.md`。三类共 17 条，DOI 均经 Crossref 核对；结论："帧占用""校准""roofline 比值"都有先例
（Davis 2007、gem5 精度评估、Roofline 2009），创新点只能落在**延迟归属可指认**与**访问粒度归因**上。
验收：表内每篇都能回答"我们与它的差异是否足以构成创新点"；若某篇已覆盖本工作全部主张，写进 §5 作为方向调整建议。
**还差**：`related-work.md` §4 列出的 3 篇全文（Electronics 2024 已看摘要，已排除撞车）。

**B2 — baseline 定义（速度/功耗/面积/集成度）。** 🚧 初版（2026-11-01，仅 C2000）
交付：`baseline.md`。已从 `doc/tms320f28p650dk.pdf` 填出速度（200 MHz、400/600 MIPS、FPU64/TMU）、
功耗（由 §6.5.1/§6.5.2 电流与 1.2/3.3 V 推导，单核 ≈0.30 W / 双核 ≈0.40 W）、面积（仅封装 81 mm²，
裸片与工艺 N/A）与集成度（2×I2C 400k、4×SPI 50M、2×6ch DMA、6×CLB）。缺口：只有一家家族、我们的功耗/
面积为零、频率不可直接比（只能比每 tick 时间）。

### C 上板

**C1 — 用 ILA 测一次真实的“请求→应答→下一次请求”。** ✅ 内存部分完成（2026-11-04）
交付：`latency-calibration.md` §2 的 C1 小节。板上实测 DDR 通路 23 拍 @100 MHz = 230 ns（典型）、
54 拍最坏、请求间隔 24 拍；模型已改为 `Periods(100 MHz, 23)`。证据：`goal/measurements/c1-ddr-latency-*.vcd`。
外设部分仍阻塞：`PlatformDevices` 的合成流清单没有 `peripheral`，arm/scan/motion 在板上不存在；
要抓外设总线，先建一条真实的外设窗口（属 D1 的 DMA/批量候选）。

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
| 设备/线的 RTL（UART 发送器、I2C 帧状态机，桥接流接 DPI） | `nzea_device/src/devices/Uart.scala`、`I2cMaster.scala` |
| 模拟器独有硬件的延迟 + 自校验计数器（`frames × 帧长 = busy`） | `nzea_rtl/src/bus/LiteBusDelay.scala`、`nzea_device/src/devices/RamFabricSlave.scala` |
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

本表是 2026-10-30 的对照；总线数字已被 2026-11-05 的 I2C RTL 取代（见 §4.1 与
`latency-calibration.md` §2）。

## 4. 关键判断（会改变计划的两个事实）

**4.1 I2C 帧进入 RTL 后，总线成为一等项（2026-11-05 修正）。**
旧判断“计算:总线 ≈ 109:1”按“每 tick 10 次 arm 访问 × 1,250 拍”算出，只框住了 arm。2M 指令的实测
（`latency-calibration.md` §2）：I2C 驻留 624,968 拍，计算 3,221,865 拍——**总线/计算 ≈ 19.4%**
（占总周期 16.2%），其中 scan 占 88%。原因是 scan 的每个字都是一次 66 bit 的读（4,092 拍 = 165 µs），
一整帧要逐字读完——所以主张应当从“总线是否重要”改为“**哪种访问粒度该上总线**”：scan 若改成
块读/DMA，这 88% 才有上限收益——这正是 D1 的 DMA/批量候选。

**4.2 上板数字不能由仿真缩放得到。**
板卡构建的 cache 是关掉的（`nzea_fpga/src/Top.scala:108-109`，`icache = None`/`dcache = None`），时钟 100 MHz（`nzea_fpga/src/logic/BootDdr.scala:82`）。计算周期不会随频率缩小，而缺 cache 会显著增大——所以板上的"计算:总线"必须重测，不能按频率比例推算。

## 5. 开放问题（未定，不写决定）

1. **"防撞车"还是"防被复现"**：讨论中的"防止复现"指"避免与已有工作重合"（则产出 B1 差异表），还是"防止他人复现我们的平台"（则要写可复现性说明）？
2. **"双重校准"**：现在指"仿真校准 + 真机校准"两条路径；RTL 与模型的分工见 `latency-calibration.md` §1。
3. **feature 题目**：D1 的四个候选中选哪个？这决定 B2 的 baseline 选型（例如选"硬件三角函数/浮点"就该对标 CLA/TMU；选"DMA/批量"就该对标 2×6ch DMA；选"可编程逻辑"就该对标 CLB）。
4. **baseline 家族**：C2000 是否作为主对标？若主对标是 RISC-V 应用处理器（如带向量扩展的核），B2 全表需重做。
5. **`timeHz` 选项**：取 `latency-calibration.md` §4 的哪个（1 = 平台自洽但动基线；2 = 不动基线、软件差 2×；3 = 现状、板卡差 10×）。
6. ~~帧位数口径~~ 已定：I2C RTL 按 32 位寄存器访问拆字节（写 56 / 读 66 bit）。

## 6. 延迟参数出处表

已交付：`latency-calibration.md` §2（含分类、逐条出处与"哪些值只存在于仿真"的结论）。
本计划不再复制表格，避免两处漂移。
