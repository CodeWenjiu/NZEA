# 活跃任务清单（roadmap）

Status: **全部搁置（2026-09-14）** — 用户决定暂停执行剩余任务；保留记录以防遗忘，重新开始时重新评估优先级。由 `goal/README.md` 的规则管理；本文件继承自已删除的 `debug-tooling-gaps.md`（G3-G7）、`board-specific-build-hooks.md`（P1-P6）、`wave-mcp-lessons.md`（W1-W5）。格式：`[状态] 任务名 — 位置/验收`。

迁移说明（2026-09-26）：核心重写到 Scala 3 + zaozi 期间，本文引用的 `nzea_*` 路径已冻结进 `legacy/`（tag `legacy-baseline`，不参与构建）；查看实现请到 `legacy/`，需运行旧流程请用 `just legacy-oracle`。

## 已完成（本文件生命周期内，勿重复提出）

| 原编号 | 内容 | 落地 |
|---|---|---|
| G1 | fpga 平台无仿真通路 → cosim 建成 | `566faf3` + `nzea_fpga/scripts/vivado-sim.nu` |
| G2 | 调试宪法第 2 条不可执行 → cosim 提供官方模型通路 | 同上；stub 保留为快速单测 |
| G8 | `ddr3_ref/README.md` 失效引用 | cosim 工作中重写 |
| 复盘 P0.5 | `ila_capture.tcl` 参数化（depth/TRIGGER_POSITION 派生） | `7cf2837` |
| 复盘 P2.13 | 板级测试程序统一管理（asm2hex + 内置程序） | `e2fc571` |
| pulse 位宽/change/无时钟回退 | ILA 导出链路缺口 | `08a2ca9`、`b61976c` |
| ILA 探针 22→11 收缩 | 删 adapter 内部 debug 探针 | `202e31f` |
| T3 probe：readback 窗口/串口自动发现/memread | G5 缺口 | `dc66360` |
| T5 goal 生命周期规则 | index + 状态规则 | `29da0b3`（`goal/README.md`） |
| W2 `is_x` + W3 `value --changes` | pulse 补 X 事件域 + 变化序列 | `34e2e36` |
| W5 `pulse plot` SVG 波形图 | 零依赖手写 SVG | `b361eb7` |
| W1 `pulse diff` 双波形对比 | 信号级 + 事件级 | `c7b9310` |

### 复盘遗留（搁置 2026-09-14）

来自 `goal/ddr3-debug-postmortem.md` 的改进点，未进本清单时已独立存在；现统一记录并搁置：

| 原编号 | 内容 | 搁置原因 |
|---|---|---|
| 复盘 P1-9 | CDC 握手可复用组件（电平握手+上升沿检测+回程确认，提炼进 `nzea_rtl`） | 当前无新跨域模块；DDR 适配器已稳定 |
| 复盘 P1-10 | 复位域矩阵（bootReset/cpuReset/ui 域显式声明） | 平台复位行为已稳定且被测试覆盖 |
| 复盘 P2-12 | MIG 关键结论存档进 `ddr3_ref/README.md`（wdf 拍数、app_addr 编码、mask=0 等） | 结论已散落在代码注释与提交中；等再次触及 DDR 时再整理 |
| pulse 性能深度优化 | 已做 `f056341`（read_bit 去分配）；property/value 路径仍有优化空间 | 当前波形规模下速度可接受 |

---

## 进行中 / 待办（**全部搁置 2026-09-14**）

### T1. ILA 链路健壮性（继承 G3，部分已修）

**搁置原因**：需上板迭代验证 + 查 PG172，当前无 debug 需求。

**位置**：`nzea_fpga/scripts/ila-capture.nu` + `FpgaElaborate.scala`（probeMap）+ `probe vcd-rename`

- 已修：TRIGGER_POSITION 硬编码（`7cf2837`）；probeMap 13 个错误条目（`7cf2837`）；探针收缩（`202e31f`）。
- **开放问题**：
  1. 语义名能否从 RTL 带到导出的 VCD（探针端口名 / `.ltx` / ILA IP 探针名属性）？**假设前必读 PG172**。
  2. `probe` 应拥有整个采集流程（arm/trigger/upload/rename）而非只 rename？
  3. VCD 是否正确导出格式？ILA 采样是周期索引，VCD 是时间戳且只记变化——CSV（一行一采样）可能匹配 `pulse --cycles` 周期语义更准。
  4. 探针接线仍硬编码在 `LxbArtix7Core`（`ila.probeN := …`），加探针要改 RTL + 全量重建。能否做成可注入配置？

### T2. remu 与 DDR 的 difftest 缺口（继承 G4）

**搁置原因**：上板 memtest 已覆盖正确性；平面参考模型会掩盖真实 MIG 时序行为；且跨仓库改动需显式决策。建议直接关闭而非重启。

**位置**：remu（独立仓库）+ `nzea_tile/src/platform/fpga/AddressMap.scala`

`remu` 的 RAM 区止于 `0x88000000`，DDR 窗口（`0x88000000..0xa8000000`）全部在参考模型之外 → DDR 测试程序无 difftest，只能自写自读对比。

**开放问题**：
1. 扩展 remu RAM（或第二区域）还是让 DDR 留在 difftest 外（真实 MIG 时序依赖行为，平面模型会掩盖）？
2. **跨仓库边界**：改 remu 必须显式决策（AGENTS.md 红线 1），不自作主张。

### T3. probe 观测手段缺口（继承 G5）

**状态**: **已实现（2026-09-14）** — 落地于 `probe/cli`（+`asm2hex` 库化）：

- `load` 增加 `--read-ms` 控制读回窗口（默认 1000ms），慢程序输出不再漏读。
- 所有子命令（load/reset/monitor/memread）端口参数可省略 → 自动经 `/dev/serial/by-id` 发现 CH340（`usb-1a86_*`，优先 `if00-port0` 别名）；端口名漂移不再需要手工发现。
- 新增 `memread <addr>` 子命令：生成一个读指定地址并以 puthex 打印的 RV32IM 小程序（asm2hex 库即时汇编），走既有 load 通路——零 RTL 改动，直接区分"残留旧数据 vs 写失败"。just 入口：`just uart-read <addr> [port]`。

### T4. 板级修改的验证分级规则（继承 G6）

**搁置原因**：当前板级改动频率低，靠人注意即可；等第二块板或 DRC 类问题再现时再立规则。

**位置**：`AGENTS.md`（验证规则段落）+ dump 期检查

只改注释/改名 → `dump` 即可；改端口名/约束 → `dump` + XDC↔RTL 交叉检查；改协议/时序 → 上板验证。交叉检查与 T7（P5）是同一套基础设施。

### T5. goal/ 生命周期规则

**状态**：**已由 `README.md` 落地（2026-09-14）**。保留此条作为指针，勿重复规划。

### T6. pulse 借鉴 wave-mcp（继承 wave-mcp-lessons.md 全部）

**状态**: **W1-W3/W5 已完成（见上表）；W4 搁置（2026-09-14）**。W4（驱动链追踪/静态网表，对标 signal_drivers/trace_value）是中期大项：

#### W1. `pulse diff` — 双波形事件级对比（对标 diff_waveforms）— 成本中

最小版：`diff --wave a --wave b` 同名信号时钟对齐采样对比，输出首分歧时刻 + 信号列表。增强版（我们的优势）：`--eval <cond>` 事件级 diff——同一事件表达式在两波形上分别求值，报告第一次计数/时刻分歧。直接服务 remu difftest mismatch 定位。要求：wellen 支持同时打开两个文件。

#### W2. `is_x` / X 态事件查询（对标 trace_x 的查询一半）— 成本低

`property` 比较遇 X 一律 false，X 在事件域是盲区。`is_x(sig)`（及 `is_z`？）内建，与 `change` 同级；X 时刻/区间可进事件表达式组合。DDR 排查期反复需要，当时只能 value 抽样。

#### W3. `value --changes` — 值变化序列（对标 signal_values）— 成本低

要两步拼（`property change()` 拿周期 + `value --at` 采样），且拿不到每点具体值。`value --changes <sig> --cycles N-M` 一次扫描输出 `(周期, 值)` 压缩序列，配 `--radix`。

#### W4. 驱动链追踪 / 静态网表（对标 signal_drivers/trace_value）— 成本高，中期

**搁置原因**：目前无具体调试需求驱动；动手前需先完成调研（firtool 能否直接输出 `.fir`、连接图规模、存储格式、与现有 scope/signal 命名对齐方式）。

wave-mcp 的差异化核心（pyslang 解析 SV 建网表 + FST 值交叉）。**我们的独特路径：不学它解析 SV**（firtool 输出全是扁平化垃圾名）——RTL 是 Chisel→FIRRTL→SV，连接关系可从 **FIRRTL 文本（connect/instance 语义明确）** 导出为静态数据库，波形值叠加。做成后免费获得：驱动链追踪、`trace_x`（追 X 源头）、无波形静态分析（仿真前当文档查 RTL）。

动手前调研：firtool 能否直接输出 `.fir`？连接图规模？存储格式？与现有 scope/signal 命名对齐方式？

#### W5. `pulse plot` — SVG 波形图输出（对标浏览器 viewer）— 成本低-中

**不引入浏览器栈**（surver/WASM 与技术栈克制冲突）。`plot --scope X --signals a,b --cycles N-M` 输出 SVG（纯文本、零新依赖），可嵌入对话/文档/commit message。手动生成 SVG 即可，不引图像库。

**开放问题**：W1 两波形 scope 层次不同（tile vs core dump）如何对齐？W2 的 X 与 Z 是否区分（ILA VCD 只有 0/1）？W5 输出放 `value` flag 还是新命令 `plot`、是否支持事件标记叠加？

### T7. 板级构建钩子机制（继承 board-specific-build-hooks.md 全部）

**状态**: **暂缓 — 触发条件：第二块板需要任一特化类型，或 `Ddr3CtrlIo` 引脚名/宽度变化迫使碰这个变换。** 当前内联块在工作且已在硬件上验证；为重构而重构无收益。

**背景**：`vivado-project.nu` 长出了 lxb_artix7 专用的内联逻辑（DDR3 处理，47–75 行），以 `<board>/ddr3_ref/` 文件存在为开关，含 5 个硬编码 XDC 端口改名替换 + 硬编码 `ip_clk_wiz.tcl` 路径。

**当前分层**（每层持有的板级知识）：

| 层 | 文件 | 当前持有的板级知识 |
|---|---|---|
| 芯片数据库 | `nzea_fpga/scripts/chips.nu` | 纯数据：vendor、板段、顶层模块、基础 XDC（`cst`）、vivado part |
| 根构建脚本 | `nzea_fpga/scripts/vivado-project.nu` | 内联特判：DDR3 处理 + `ip_clk_wiz.tcl` 路径 |
| Edalize 包装 | `nzea_fpga/tools/vivado/vivado-project.py` | 通用 flag：`--xdc`（可重复）、`--ip-tcl`（可重复）、`--mig-prj`（可选单值） |
| Scala 生成 | `nzea_fpga/src/FpgaElaborate.scala` | `FpgaBoard` match；Gowin `renameAluForGowin` 后处理；`debugIla` 时写 `ila_config.json`/`ila_capture.tcl`/`ila_probes.map` |

**已观察的问题**：

- **P1** — DDR3 是唯一需要*变换*的特化（clk_wiz/ILA 都是"文件在场"约定；DDR3 需要脚本派生一个重写的 XDC）。开放问题：让 DDR3 与其他一致，还是确立"变换"机制？
- **P2** — RTL 位宽知识泄漏进构建脚本：4 个替换是剥离 `[0]` 下标（`ck_p`/`ck_n`/`cke`/`odt` 在 `Ddr3CtrlIo` 里是 `UInt(1.W)` → firtool 塌成标量）。改 RTL 端口形状（1 元素向量贴合 vendor 模板）还是模板适配 RTL？哪个成本低且能统一成前缀改名？
- **P3** — 变换是钝器且不跟踪改名：`str replace -a "ddr3_" "DDR3_"` 也改注释里的 167 处之一；`Ddr3CtrlIo` 字段改名后 XDC 只报"端口不匹配"警告。开放问题：构建期变换能否跟踪 RTL，或从 Scala bundle 定义派生规则（像 `ila_config.json` 从 `IlaProbes.widths` 派生）？
- **P4** — 开关是隐式且单向的：DDR3 开关 = 两个参考文件存在；`chips.nu` 无任何标记；关闭 = 删文件。文件在场驱动是特性（ILA 同哲学）还是负债（不可发现）？
- **P5** — 约束/RTL 不一致暴露太晚：bank 类型冲突（`[DRC BIVB-1]`）到 Vivado DRC 才出现（综合之后）。XDC↔RTL 一致性应在哪验证、由谁验证？dump 期最便宜检查是什么？此条与 T4 同基础设施。
- **P6** — 不对称在 nushell 层：Python 层很可能只需让 `--mig-prj` 可重复（万一未来两内存 IP 实例）。

**已枚举的机制空间（刻意不排序）**：
- **A. 约定式 nushell 钩子** — 可选 `<board>/build-hooks.nu` 导出固定契约（`prepare [rtl_dir] -> record`），根脚本 source。命令式自由但无类型检查。
- **B. 数据驱动描述符** — `chips.nu` 板记录加结构化字段（`extra_xdcs`/`mig_prj`/`ip` 列表）；脚本解释已枚举 kind，未知 kind 硬报错。声明式、可 diff。
- **C. Scala 侧板 trait** — `FpgaBoard` 返回构建描述符写成 JSON；脚本变哑执行器。类型检查、与 part 名/探针宽度同处；有与 `chips.nu` 分叉风险。
- **D. 消除变换** — 离线跑一次重写，把结果作为板级 XDC 提交（只留 `mig_b.prj` 作 vendor 输入）。逻辑消失；漂移成为开放风险。

**实现前必答**：端口名契约归谁（RTL bundle/vendor 模板/提交的 XDC）？钩子允许代码还是只文件+数据（A vs B）？`chips.nu` 单一数据库还是按板 source？edalize 文件集模型是否已吸收类型 2-4（P6）？`renameAluForGowin` 并入同机制还是保留 Scala 特例？

**特化类型登记册**（现状记录，非正确抽象声明）：约束变换 / vendor 配置生成 IP（MIG）/ 板级 TCL 生成 IP（clk_wiz）/ RTL 例化驱动 IP（ILA）/ 附加约束文件 / 后 RTL 处理（Gowin ALU 改名）/ 器件时钟解析（已数据驱动）。

---

## 相关

- `goal/README.md` — 目录索引与生命周期规则。
- `goal/ddr3-debug-postmortem.md` — DDR3 接入与间歇性排查的完整历史证据。