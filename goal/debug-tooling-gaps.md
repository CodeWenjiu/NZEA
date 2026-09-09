# 调试工具链与 FPGA 验证缺口（开放问题，暂缓）

Status: **已暂缓 — 只记录问题**。

来源：从 DDR3 接入复盘（`goal/ddr3-debug-postmortem.md`）加上之后的工具链审查蒸馏而来。两份文档分工：复盘记录*发生了什么*；这份记录调试工具链（`probe`、`pulse`、remu）和 nzea 流程里*结构性缺失的东西*。

不记录决定或建议，让后来的工作——会有更多板子和更多证据——不被单一接入经历得出的结论锚定。本文件与复盘不一致处，以开放问题而非裁决形式陈述。

## 本文件依赖的已验证事实

下面每条都对照仓库核查过，不是凭记忆：

| 事实 | 证据 |
|---|---|
| fpga 平台没有 iverilog 仿真通路 | `AGENTS.md`，`### 仿真（iverilog）`："`just iv tile yosys <isa>` … **仅 yosys 平台**——fpga 平台设备在板外/平台 IP，TB 未覆盖" |
| MIG 仿真 RTL 在工程创建时已被请求 | `nzea_fpga/tools/vivado/vivado-project.py`：生成的片段以 `generate_target all [get_ips mig_ddr3]` 结尾（`all` 含 Simulation target） |
| 官方 Micron DDR3 模型已在仓库 | `nzea_fpga/src/boards/lxb_artix7/ddr3_ref/sim/{ddr3_model.sv,ddr3_model_parameters.vh,wiredly.v}` |
| 存在四个手写 MIG 行为 stub | `nzea_device/test/src/ddr3/Ddr3AdapterTest.scala`：`MigAppStub`、`MigAppStubWithCalib`、`MigAppStubSwallow`、`MigAppStubHold` |
| 调试宪法第 2 条恰好禁止这个 | `AGENTS.md`，`### 调试宪法`："**仿真模型必须官方**：禁止手写 IP 行为模型（如 MIG、MMCM、PLL）。必须使用 Vivado 生成的官方仿真文件" |
| remu 的 RAM 区域恰好结束在 DDR 开始处 | remu 启动日志：`ram region initialized at 0x80000000:0x88000000`；`nzea_tile/src/platform/fpga/AddressMap.scala`：`val ddr = LiteAddrRange(base = 0x88000000, size = 0x20000000)` |
| `probe load` 没有读回窗口控制 | `probe/cli/src/main.rs`：`Cmd::Load { hex, port, baud, addr }`，docstring "…then read back **briefly**" |
| ILA VCD net 名是综合产物 | `nzea_fpga/src/FpgaElaborate.scala` `probeMap` 注释：firtool 垃圾名（`E`、`SR`）、常量折叠（`<const0>`）、地址拆分（`_2`/`_1`）；旧映射有 13 个条目名是错的 |
| `TRIGGER_POSITION` 曾硬编码在采集深度之上 | 已修于 `7cf2837`（原来是 32768 vs depth 16384；现在从 `IlaProbes.depth` 派生） |

## G1 — fpga 平台完全没有仿真通路

任何板级的东西（MIG、MMCM、DDR 物理层、ILA）只能通过硬件迭代验证，一轮大约 10–20 分钟（`dump` → `vivado-build` → `program` → `load`）。复盘估计 DDR3 接入跑了 15+ 轮这样的迭代。

复盘把它归结为"stub 不够精确"（它的 P0 1–2 项：让 MIG stub 精确、为每个观察到的硬件怪癖加 stub 变体）。**开放分歧**：stub 的语义来自我们对真实 IP 的*猜测*，所以一个错误猜测会产出一个忠实实现错误协议的 stub。Bug 1（`app_wdf_data` 到底驱动一拍还是两拍）能骗过仿真，正因为 stub 和 RTL 出自同一个错误假设。更多的 stub 变体 = 更多的猜测，不是更少。

注意真实仿真的原料已经存在（见事实表）：`generate_target all` 已经被调用，所以 MIG 的仿真 RTL 由一次工程创建产生——比完整构建便宜得多——官方 Micron DDR3 模型也已提交。缺的是 testbench 和 recipe，不是 vendor 文件。

**未验证假设，作为风险记录而非计划**：开源仿真器能否编译 MIG 7-series RTL 未知。预期 iverilog 对付不了 Xilinx IP；`xsim`（随 Vivado 提供、无需额外许可、必然能编译自家 IP）是可能的路线。这还没试过。

开放问题：
1. 缺失的能力是"更忠实的 stub"还是"通往官方模型的通路"？两者意味着不同的工作量和不同的失败模式。
2. 用哪个仿真器，答案是否因 IP 而异（MIG vs MMCM vs ILA）？
3. 这样的 cosim 该放 `just iv` 下（扩展平台矩阵）还是独立 recipe，考虑到它的仿真器是 Vivado 提供的、不是 iverilog？
4. 如果官方模型可用，`Ddr3AdapterTest` 的四个手写 stub 怎么办——删除，还是保留为以 cosim 为权威的快速单元级检查？

## G2 — 调试宪法第 2 条目前无法遵守

`AGENTS.md` 禁止手写 IP 行为模型，要求 Vivado 生成的官方仿真文件。`nzea_device/test/src/ddr3/Ddr3AdapterTest.scala` 里有四个手写 MIG stub，仓库里没有任何流程能消费官方 MIG 模型。

这记录为**规则/工具链错配**，不是要追责的违规：没有通往官方模型的通路，这条规则从写下那一刻起就不可执行，DDR3 工作没有别的选择。

开放问题：
1. 应该放宽规则（说明什么时候 stub 可接受）还是建工具（让规则可执行）？无法遵守的规则会训练读者无视整个宪法。
2. 如果保留 stub，什么标记它"是猜测、不是权威"，让 stub 通过不产生虚假信心？复盘点明：一个通过但错误的 stub *比没有 stub 更危险*。
3. MMCM（`Mmcm50to200`）和 Gowin 通路是否也有同样的缺口？

## G3 — ILA 链路是三件工具粘在一起，且以综合后的 net 名为键

`just ila-capture` 是一个 nushell 脚本（`nzea_fpga/scripts/ila-capture.nu`）驱动 Vivado batch TCL（`ila_capture.tcl`），其 VCD 输出再由 `probe vcd-rename` 用 `ila_probes.map` 改名。`probe` 只拥有最后一步。

这次接入浮出两个缺陷：

- `TRIGGER_POSITION` 硬编码为 32768，对 16384 的深度，所以每次采集都报错直到手工改。已修于 `7cf2837`（从 `IlaProbes.depth` 派生）。
- `ila_probes.map` 有 13 个条目名是错的，导出的 VCD 信号名是垃圾。已修于 `7cf2837`（对照一次实际采集重写映射）。

第二个修复是手工修复，不是机制：**被映射的名字是综合产物**（firtool 垃圾名、常量折叠、拆分向量），所以映射会在下一次构建时静默失配。目前记录在案的缓解措施是一条注释，告诉读者用 `pulse signal` 重新推导名字。

另外（复盘第 3 项）：探针接线硬编码在 `LxbArtix7Core`（`ila.probeN := …`），所以加一个探针要改 RTL 加一次完整重建。这和名字脆弱是独立的问题——一个是"改探针很贵"，一个是"探针名不可靠"。

开放问题：
1. 语义名能否从 RTL 带到导出的 VCD，而不是事后重建（`IlaProbes` 上的探针端口名、从 `.ltx` 读名字、或 ILA IP 探针名属性）？**假设任一可行之前必须先读 PG172。**
2. `probe` 应该拥有整个采集流程（arm、trigger、upload、rename）而不是只有 rename，让链路只有一个主人吗？
3. VCD 是正确的导出格式吗？ILA 采样是周期索引的，而 VCD 是时间戳的、只在变化时写，复盘指出这让静态窗口看起来只有几 ns。Vivado 还能导出 CSV，其一行一采样的形状匹配 `pulse --cycles` 假设的周期语义。（相关：`pulse` 在 `08a2ca9` 加了无时钟回退，正因为 ILA 导出没有可用时钟。）

## G4 — remu 看不见 DDR，所以 DDR 测试没有 difftest

remu 把 `ram` 初始化为 `0x80000000:0x88000000`；`AddressMap.ddr` 起始于 `0x88000000`——DDR 窗口完全落在 remu 地址空间之外。后果：DDR 测试程序只能以裸 hex 通过 `just uart-load` 运行，没有参考模型，正确性完全依赖程序拿自己的写和自己的读对比。

开放问题：
1. 扩展 remu 的 RAM 区域（或加第二个区域）是正确回应吗，还是 DDR 该留在 difftest 之外——因为真实 MIG 有时序依赖行为、平面参考模型会掩盖它？
2. **跨仓库边界**：remu 是独立仓库，有自己的约定（`AGENTS.md` 红线 1 禁止跨仓库推断一致性）。那边的任何改动都需要显式决定，不是这边好心去"修"。

## G5 — probe：load 没有读回窗口，串口漂移

- `Cmd::Load` 只暴露 `hex`、`port`、`baud`、`addr`；读回窗口固定且短。接入期间观察到的症状：程序其实在跑，却返回 `(no data)`，看起来完全像挂死。`Cmd::Monitor` 已经有 `--reset`（先 attach 再复位），所以正确的观测姿势存在，但从 load 路径够不着。
- 端口名漂移（`ttyUSB0` ↔ `ttyUSB1`）。`ila-capture` 默认用 `/dev/serial/by-id/...` 路径，但 `just uart-load` 仍要求显式传端口，所以每次板级会话都从重新发现端口开始。

复盘第 6、8 项；都还开着。第 7 项（一个读任意 DDR 地址的 `probe` 子命令，区分"残留"vs"写失败"）也还开着。

## G6 — 没有"哪些改动需要板上验证"的规则

`Ddr3CtrlIo` bundle 重构（`7cf2837`）改变了生成顶层端口的*声明顺序*，但名字全部相同。便宜能验证的是"名字不变，所以引脚 XDC 仍匹配"；不经过 ~15 分钟构建加一轮上板无法验证的是"行为不变"。提交如实记录了这个缺口，而不是多声称。

复盘第 4 项讨论构建成本（从 dcp checkpoint 增量复用）；这是相邻的缺失件——**验证分级规则**，例如：仅注释/改名 → `dump` 足够；端口名或约束 → `dump` 加 XDC↔RTL 交叉检查；协议或时序 → 上板。交叉检查那一半和 `goal/board-specific-build-hooks.md` 里的 P5 是同一套基础设施。

## G7 — `goal/` 没有索引或生命周期规则

`goal/` 里现有四份文档，状态各异（两份暂缓、一份已实现、一份复盘），项目历史上还删过至少四份（`config-aggregation`、`config-single-source`、`data-path-info-units`、`pulse-evolution`）。`AGENTS.md` 为 `.pulse` 事件文件定义了生命周期原则（"用到再说"），但对 `goal/` 只字未提。

具体症状：写 `goal/board-specific-build-hooks.md` 时，把一份已删除的文档（`config-aggregation.md`）当作先例引用，核查后不得不撤回。仓库里除 `ls` 外没有任何东西让这个可发现。

开放问题：goal 文档有哪几种状态、谁在它们之间转移、索引（`goal/README.md`）还是每份文档的状态行是让它们保持诚实的最便宜方式？

## G8 — `ddr3_ref/README.md` 的失效引用（已解决）

`7cf2837` 提交时未核查。三处引用是错的：

| README 里的引用 | 实际状态 |
|---|---|
| `goal/ddr3-bus-device.md` | 不存在 |
| `srcs/sim_1/new/tb_ddr3_loop_test.v` | 声称包含；**不在**拷贝的树里 |
| `DdrBridge` | 已改名为 `Ddr3Adapter`；README 是唯一还用旧名的地方 |

**已在 cosim 工作中解决**：README 重写为描述实际内容、删掉不存在的 goal 文档、停止 vendoring Micron 模型（`sim/`，Vivado 反正会重新生成）。缺失文件条目指向的 vendor TB 被证明对 G1 并不必要——cosim 直接复用重新生成的 `sim_tb_top.v`。

根本问题和 G7 同类、未改变：没有检查能确保文档保持诚实。

## 已落地——不要重复提出

| 复盘项 | 落地于 |
|---|---|
| 5 — 参数化 `ila_capture.tcl`（depth / TRIGGER_POSITION） | `7cf2837` |
| 13 — 板级测试程序管理 | `e2fc571`（`probe/asm2hex` + 内置程序） |
| pulse 位宽感知、change 内建、无时钟回退 | `08a2ca9`、`b61976c` |
| ILA VCD → pulse 链路 | `ff2ffa6`（`probe vcd-rename`） |

复盘里仍开的：1–2（重构见 G1）、3（G3）、4（G6）、6–8（G5）、9（CDC 握手做成可复用的 `nzea_rtl` 组件）、10（复位域矩阵）、11–12（参考设计覆盖盲区、MIG 事实存档）。

## 相关

- `goal/ddr3-debug-postmortem.md` — 本文档抽象的接入叙事（应要求为中文）。
- `goal/ddr3-debug-port-removal.md` — 宽的 `Ddr3AdapterDebug` 端口之所以存在，正因为 G1/G3 让 ILA 成为窥探适配器的唯一窗口。
- `goal/board-specific-build-hooks.md` — 那边的 P5（dump 期 XDC↔RTL 端口交叉检查）是 G6 需要的基础设施。
- `goal/pulse-widths-and-change-stats.md` — 已实现；它解决的 ILA 导出问题和 G3 的格式问题是同一家族。
