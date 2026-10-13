# 核心基线：最简 in-order 核

Status: **基线已落地（已重写到 Scala 3 + zaozi）** — 三段式 in-order 基线（`NzeaCore` = Front + Middle + Back），
500 MHz 约束下 `just sta` 通过，remu difftest（core 级）通过。后续微架构按"挂载顺序"逐步加回。

## 目标形态（三段）

```
前端 = 取指 + 译码（+BP 插件，基线没有）
中端 = 分发/重排 + 提交（顺序端）    ← 多态点：in-order 中端 vs 未来 OoO 中端
后端 = 执行 + 写回
```

- in-order 是同一合同的退化实现：ARF 当 PRF，无 RAT/ROB，提交=顺序完成。

## 当前结构

```
nzea_core/src/
  NzeaCore.scala        顶层：sim 时三个总线接 DPI 桥；--sim false 时把 ibus/dbus/commit 暴露为端口（综合顶层）
  front/                Front（取指 + 译码，两段之间的流水寄存器由它 own，受 flush 控制）
    ifu/                Ifu（pc/epoch，一次一个请求，预测随取指）/ IbusUser（user = {pc, next_pc, epoch}）
    idu/                Idu（PLA 译码 + 立即数成形 + 源寄存器号）/ inst/（Insts 类型与模式形状 ｜ InstI、InstM 指令行，各自 rows(xlen) 选行）/ DecodeTables（真值表 → PLA）
  middle/               Middle（三级：Isu / Exu / Wbu + 两个边界寄存器 + 共享寄存器文件 rf）/ ExecSlot（IS/EX 载荷）
    rf/                 Arf（GPR 文件：两读口 + 一写口，纯存储）/ CsrFile（机器态 CSR：组合读（含写穿透），写随提交）
    isu/                Isu（读 + 组装，纯组合；写 IS/EX 写侧）+ OperandRead（寄存器读与等待）+ Assemble（uop 组装）；issue.pulse
    exu/                Exu（读 IS/EX 读侧，把 slot 呈现为后端 uop 视图）+ Dispatch（分发 + 完成合并）+ Completion（完成路径 + LSQ）+ Forward（前递网络）+ Lsq；uop.pulse
    wbu/                Wbu（写回寄存器：提交、flush、redirect 的源头，驱动文件的写面）；retire.pulse
  back/                 执行单元与契约：IntFace / MemFace / QueueFace / IssueSlot / IssueUop / PathResult / OpWindow / RetireEvent / BpUpdate
    int/                IntPath（ALU/BRU/MUL/DIV/SYSU + 分支计数）/ Fu（FuType 与 IntOp 窗口）/ FuOps（各 op 编码）/ Mul / Div
    mem/                MemPath（LSU：请求保持 + 响应 tag 匹配）/ LsuOp（LsuOp 与 MemOp 窗口）/ DbusUser
  isa/                  跨段 ISA 级类型：Backend / CsrType / Gpr / Operands（操作数形态 Srcs）
  stats/                仿真计数器声明与 schema（CoreStats / Counters）
```

- 编码机制在 `nzea_rtl/src/stdlib/Encoding.scala`：`Encoding` 是"字段编码"，`Encoding.OneHot` / `Encoding.Sequential`
  把 `lit` 从声明序导出来（不再手写 `1 << n`），`Encoding.Column` 是窗口列；`OneHotSet`（挂在 enum 伴随上）提供
  整套 `mux1H`。

## 合同（跨模块）

- 前端→中端 `IduOut`（**架构级**）：`pc / next_pc / fall_through / imm / rd_index / rs1_index / rs2_index / srcs（操作数形态）/ backend / op 窗口 / csr_addr`。
  译码只出"指令是什么"；ARF 读与操作数槽位解析在中端发射拍完成（`Srcs.resolve`），读到的就是文件当时的内容。
- 中端→后端 `IssueUop`（**就绪操作数**）：三个槽位 + `pc`/`next_pc`/`fall_through`/`imm`（结构用途）+ `backend`
  + `op` 窗口 + `csr_type`/`csr_rdata`。`next_pc` 是取指预测的下一 pc（基线 = 顺延），`fall_through` 是该指令自己的
  `pc + 4`（`constAdder(pc, 4)`，取指端算好后经 user 字段随响应回送）——两者都随取指携带，后端任何阶段都不重建 `pc+4`。
  后端不做源选择，只按自己的 op 读槽位：
  ALU 读 a/b；BRU 比较 a/b、JALR 用 a 当目标基址；SYSU 的写数据与"零源不写"判定读 a；MUL/DIV 读 a/b；
  LSU 地址 = a+b、store 数据 = c。
- 后端→中端 `PathResult`（完成通道，Valid）：`data / next_pc / csr_type / csr_data / is_mmio / is_branch /
  pred_next_pc`。后端**没有流水状态**：uop 由中端的 IS/EX 寄存器按住，完成载荷由中端的写回寄存器采样；误预测
  比较（`is_branch & next_pc =/= pred_next_pc`）也在那个寄存器上做，不在后端的组合锥里。
- 中端→前端：`redirect`（Valid，显式事件，同时是唯一让 `Ifu` 自增 epoch 的原因）+ `flush`（广播，给流水寄存器）。
- 后端窗口：`IntOp` = `fuOp | fuType`，`MemOp` = `LsuOp`；二者低锚定、互相别名，共享 `op` 字段的宽度 = `OpWindow.Width`。
- 中端→顶层 `commit`（`CommitMsg`）：架构状态变化的轨迹（`next_pc`/`rd_index`/`rd_value`/`is_mmio`/`csr_valid`/
  `csr_addr`/`csr_data`）。CSR 报的是**架构事实**（地址 + valid），不是内部的 `CsrType` 编码，所以消费者（debugger、
  仿真器桥）不需要知道 core 的 ISA 类型；core 本身无 DPI。

## 基线行为

- **流水线：IF / ID / IS / EX / WB，五级四个寄存器**（`pipelineConnect` 拥有 IF/ID、ID/IS、IS/EX 三个，均为
  `name` 显式的弹性寄存器；WB 是朴素 `Reg`，因为它的条目是“取消别人的那一条”，不被取消也无反压）：

  | 级 | 寄存器 | 在哪 | 级内组合 |
  |---|---|---|---|
  | IF | `ifu.pc` + 总线往返 | Ifu | 请求/响应、epoch 甄别、预测（`pc+4`）随请求携带 |
  | ID | `ifid`（指令字） | Front | `Idu` 的 PLA + 立即数 |
  | ID/IS | `idis`（译码载荷） | Middle | 寄存器读与组装（`OperandRead` + `Assemble`，即 `Isu`） |
  | IS/EX | `isex`（`ExecSlot`） | Middle | uop 视图（`Exu` 读侧）+ 执行（IntPath/MemPath 组合） |
  | EX/WB | `wb`（结果） | Middle | 写回：`arf`/`csr` 写 + commit + flush/redirect 广播 |

  后端的“级数”完全由单元自己声明（ALU/BRU/SYSU 组合、MUL 3 拍、DIV 多拍、LSU 变长）。
- `pipelineConnect` 的 flush 策略显式化（`FlushHandover`）：前端 IF/ID 取 `Consume`——它的生产者是总线响应，
  响应被拒会挂在总线上堵住下一拍；中端两个寄存器取 `Refuse`——它们的生产者是同级流水，拒收让取指不在被杀的路上
  白取一个词。（实测：若把中端也改成 `Consume`，每次 flush 多花≈1 拍；若把前端改成 `Refuse`，每次 flush 也多花≈1 拍。）
- 顺序单发射；取指预测 = 顺延（always-not-taken）。预测与顺延值随取指走完全程（`FetchedInst`→`IduOut`→`ExecSlot`→
  `IssueUop`），BRU 用预测与解析结果对照（基线下该对照折叠成 `offset =/= 4`，目标加法器不进 redirect 的 valid
  锥）；分支在 BRU 解析后 flush + redirect；无 BPU。BP 落地时替换 `Ifu` 里那一个预测值，管道其余不动。
- 中端两槽：`idis`（译码载荷，还没有操作数）与 `isex`（`ExecSlot`：就绪 uop + `rd_index`/`csr_addr`；后端只看到
  它反推出的 `IssueUop`）。两个寄存器与 `pipelineConnect` 都在 `Middle`：`idis` 夹在 `Isu`（读 + 组装：`OperandRead`
  的操作数读取与 `Assemble` 的 uop 组装，与前端 `Idu` 同规矩）与前端之间，`isex` 的写侧是 `Isu` 的、读侧是 `Exu` 的
  （`Exu` 把 slot 呈现成后端 uop 视图：角色位、`csr_type`、`csr_rdata`），即每个寄存器都夹在两个流水级之间，而不是
  某个单元内部。载荷在后端 uop 完成的那拍搬入 `isex`
  并在同一拍读寄存器：此时写口上正是那条在途指令的写，读一次就是对的。载荷读的正是这条指令写的寄存器时，
  搬运晚一拍（写口下一拍才到），即 "读永远正确" 的代价。共享的 `Arf`/`CsrFile` 挂在 `Middle` 下：读面面向 `Isu`
  （Arf）与 `Exu`（CSR，地址随 slot），写面由 `Wbu` 驱动。
- 前递网络是一个按档位裁剪的源清单：`BypassLevel` 取转发阶梯的年轻前缀——`Commit` 档 = {提交中的写}，
  `Execute` 档 = {完成中的结果, 提交中的写}（冲突取年轻者），`None` = 空。源各自进 `back.Bypass`（完成结果在
  back 本地，提交写经 `BypassBus` 从中端带来），逐读口给 `ValidIO`；`OperandRead` 以 `valid ? bypass : arf` 选择。
  `Arf` 是纯存储（无写穿透 mux）。
- 等待与源清单对偶：网络上没有的层就是读要等的层——不到 `Execute` 档读在飞执行槽则等，不到 `Commit` 档读提交中的写
  则等；被覆盖的等待项连同比较器在构建期折叠。
- CSR：地址随 IS/EX 槽锁存（`isex` 的 `csr_addr` 字段），读是组合读，写只发生在提交拍；flush 不污染架构状态。
- 总线纯 Decoupled：取消/甄别全在 master 侧（取指用 epoch、LSU 用请求 tag）；reset 期间的流量拒绝是 fabric 的职责。
- 异常/中断：**不做**。`--flow verilator` 时每个计数器一个 `public_flat_rd` 黑盒（模块名即计数器名），名字由声明处的
  scope 拼成：`stat_core_middle_cycle` / `stat_core_middle_inst_commit`（`Wbu`）与 `stat_core_back_int_bp_branch` /
  `stat_core_back_int_bp_mispred`（IntPath），派生 `ipc` / `accuracy`，schema 随 dump 一起产出（见
  `stat-schema.md`）。

## 基线测量（2026-09-29）

- riscv32i / icsprout55 H7CL tt / 2 ns 约束：面积 21164.64，最差路径 **1.136 ns**（IS 拍自己的 `pc+4` 键：
  `dec.pc → +4 → isex_pc_plus4`；提交 `next_pc` 那端 1.137 ns 紧随其后，锥只剩 op 译码 → BRU 目标基址 mux →
  分支目标加法器），等价 ~848 MHz，slack 0.820 ns；riscv64i：面积 42317.52，最差路径 1.323 ns
  （~730 MHz 等价，slack 0.630 ns）。flop 数 1732（32i）/ 3342（64i）：位总数不变，多出的两个是
  `pc_plus4[1:0] ≡ pc[1:0]` 的合并在寄存器载荷重组后没再被 yosys 合并（两个重复 DFF）。
- 逐点对照（同一流程复测，riscv32i 最差路径）：1.190（重构前）→ 1.205（流水级显式化）→ 1.137（`pc_plus4`）
  → 1.128（ISU 抽取）→ **1.136**（两级寄存器归 Middle）；riscv64i：1.285 → 1.427 → 1.410 → 1.321 → **1.323**。
  各步 ±0.1 ns 量级的跳变都是 yosys-sta 对（逻辑等价的）网表做的映射/驱动选择差异，不是逻辑变化。
- IPC（microbench `test`）：riscv32im 0.3412 / riscv32i 0.3711 / riscv64im 0.3332（差异来自相邻依赖对多等一拍）。
  确定性探针 `nzea_core/test/asm/branch_loop.S`（32 迭代的 taken 回边 + 一次 load/store）：463 拍 / 167 条 /
  ipc 0.3607，改动前后逐拍一致。
- difftest：remu `--platform nzea --sim-opt target=core`，microbench `test` 10/10（32i / 32im / 64im），
  CSR 读写汇编回归 32im / 64im 通过。
- 前递网络（2026-09-30）：`Commit` 档 32i 1.154 ns（~837 MHz）/ 64i 1.353 ns（~715 MHz），面积 21105.28 / 41964.72，
  与重构前的差在 ±0.03 ns 映射噪声带内；`Execute` 档 32i 1.211 ns（~797 MHz）/ 64i 1.450 ns（~669 MHz），面积
  21268.80 / 42276.36，微基准 IPC 0.3429（与内联实现逐拍一致，difftest 全过）。
- 源清单 + 读单元（2026-09-30，同日）：源的集合改为档位前缀的 `Seq[ForwardSource]`（`forwardRead` 归约），`Arf` 去写穿透，
  `Isu` 的读与等待拆到 `OperandRead` 单元（`Isu` 只剩 uop 组装）。`Commit` 档 32i 1.134 ns（~851 MHz）/ 64i 1.336 ns
  （~724 MHz），面积 21044.52 / 41833.96（较上一步 -0.3%）；`Execute` 档微基准 IPC 0.3429，difftest 全过；
  `branch_loop` 与基线逐拍一致（463/167/0.3607）。
- 预测随取指（2026-09-30，同日）：`pc_plus4` 退役，改由取指产出 `next_pc`（`IbusUser` = {pc, next_pc, epoch}，请求携带、
  响应回送；`Isu` 的 `pc+4` 加法器下线）。行为逐拍不变：`dep_chain` 138/133（Execute + 弹性桥基线的同值）、
  `branch_loop` 391/167 与改动前网表逐拍一致（用旧网表 .so 复跑核对；此前记录的 463 是弹性取指桥落地之前的值），
  microbench `test` 32im 0.4142 / 64im 0.4100，difftest 全过。STA：32i 最差 1.181 ns（取指回路 `pc → +4 → mux → pc`，
  别名到 `ibus_req_bits_addr` 端口寄存器；上一步的最差键是 IS 拍 `pc+4` 1.156，已随加法器消失），面积 21535.08（+0.57%）；
  64i 最差 1.359 ns（`src_b/c` 1.415 → 1.359），面积 43078.28（+1.54%）；flop 32i 1732 → 1796（+64，均为 ifid/idis
  携带 `next_pc`）。

## 挂载顺序（每步正交、独立验证）

1. 基线（无 BP in-order）→ 验证三段合同 ✅
2. 挂 BP：前端取指器加回 PHT/BTB/RAS（合同点在 `Ifu` 内部）——M2a（`Bimodal` = PHT + BTB）已落地，见
   `goal/branch-prediction.md`；RAS 与 TAGE 待续
3. 挂 OoO 中端：RAT+PRF+ROB+IQ 重写 `Middle`（前/后端不动；操作数槽位合同不变，槽位改由 PRF 读 + wakeup 产生）
4. 向量后端：后端扩展（`BackOut` / `MemPath` 的契约点）

## 待定问题

- in-order 中端是否需要一个极小退休缓冲来承载将来的精确异常（基线先"完成即提交"）。若做，它与 OoO 的 ROB 是否共享同一合同语义？
- store 完成语义：基线等 resp 才提交（保证 MMIO store 完成）。未来 OoO 的提交/访存顺序不同，"store 完成"如何在合同上统一表达？
- 标签位宽：`DbusUser.tagWidth`、`IbusUser.epochBits` 现为常量；重命名/多发射时这些宽度定义在哪（是否随 `CoreParams` 协商）？
- 操作数槽位是三个（store 刚好用满）；V 扩展 / FMA 这类三源以上的操作是加第 4 槽，还是让该后端自带私有槽位？
- 预测经请求 user 字段随响应回送（无状态、handover 安全），代价是 ibus user 宽度 36→68（32i）且取指回路的 `pc+4`
  现在也驱动该端口（1.181 ns 的最差键）。另一种做法是 IFU 持一个"未决预测"侧寄存器（总线不变，+32 flop，但正确性
  要论证单未决 + 同拍 handover 的边界）；BP 落地时再评估。
