# 核心基线：最简 in-order 核

Status: **基线已落地（已重写到 Scala 3 + zaozi）** — 三段式 in-order 基线（`NzeaCore` = Front + Middle + Back），
500 MHz 约束下 `just synth` / `just sta` 通过，remu difftest（core 级）通过。后续微架构按"挂载顺序"逐步加回。

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
    ifu/                Ifu（pc/epoch，一次一个请求）/ IbusUser（user = {pc, epoch}）
    idu/                Idu（PLA 译码 + 立即数成形 + 源寄存器号）/ inst（指令表）/ DecodeTables（真值表 → PLA）
  middle/               Middle（ARF 读 + 发射槽 + 提交，多态点）/ Arf（2 读口 + 1 写口 + 写回链接）/ CsrFile / CommitMsg
  back/                 Back（按 backend 分发 + 完成合并 + 出口锁存）/ IssueUop / BackOut / PathResult / OpWindow
    int/                IntPath（ALU/BRU/MUL/DIV/SYSU + 分支计数）/ Fu（FuType 与 IntOp 窗口）/ FuOps（各 op 编码）/ Mul / Div
    mem/                MemPath（LSU：请求保持 + 响应 tag 匹配）/ LsuOp（LsuOp 与 MemOp 窗口）/ DbusUser
  isa/                  跨段 ISA 级类型：Backend / CsrType / Gpr / Operands（操作数形态 Srcs）
  stats/                仿真计数器声明与 schema（CoreStats / Counters）
  dpi/                  ibus / dbus / commit 的 DPI 桥
```

- 编码机制在 `nzea_rtl/src/stdlib/Encoding.scala`：`Encoding` 是"字段编码"，`Encoding.OneHot` / `Encoding.Sequential`
  把 `lit` 从声明序导出来（不再手写 `1 << n`），`Encoding.Column` 是窗口列；`OneHotSet`（挂在 enum 伴随上）提供
  整套 `mux1H`。

## 合同（跨模块）

- 前端→中端 `IduOut`（**架构级**）：`pc / imm / rd_index / rs1_index / rs2_index / srcs（操作数形态）/ backend / op 窗口 / csr_addr`。
  译码只出"指令是什么"；ARF 读与操作数槽位解析在中端发射拍完成（`Srcs.resolve`），读到的就是文件当时的内容。
- 中端→后端 `IssueUop`（**就绪操作数**）：三个槽位 + `pc`/`imm`（结构用途：`pc+4`、分支偏移、BRU 目标基址）
  + `backend` + `op` 窗口 + `csr_type`/`csr_rdata`。后端不做源选择，只按自己的 op 读槽位：
  ALU 读 a/b；BRU 比较 a/b、JALR 用 a 当目标基址；SYSU 的写数据与"零源不写"判定读 a；MUL/DIV 读 a/b；
  LSU 地址 = a+b、store 数据 = c。
- 后端→中端 `BackOut`（完成报告）：`complete / done / data / next_pc / csr_type / csr_data / is_mmio / flush / redirect_target`。
- 中端→前端：`redirect`（Valid，显式事件，同时是唯一让 `Ifu` 自增 epoch 的原因）+ `flush`（广播，给流水寄存器）。
- 后端窗口：`IntOp` = `fuOp | fuType`，`MemOp` = `LsuOp`；二者低锚定、互相别名，共享 `op` 字段的宽度 = `OpWindow.Width`。
- 中端→顶层 `commit`（`CommitMsg`）：架构状态变化的轨迹（`next_pc`/`rd_index`/`rd_value`/`is_mmio`/`csr_*`）。

## 基线行为

- 顺序单发射；取指永远 `pc+4`（always-not-taken），分支在 BRU 解析后 flush + redirect；无 BPU。
- 中端两槽：`dec`（译码载荷，还没有操作数）与 `ex`（后端正在执行的 uop）。载荷在后端 uop 完成的那拍搬入 `ex`
  并在同一拍读寄存器：此时写口上正是那条在途指令的写，读一次就是对的。载荷读的正是这条指令写的寄存器时，
  搬运晚一拍（写口下一拍才到），即 "读永远正确" 的代价。
- 写回链接（`BypassConfig.writeback`）就是 ARF 自己的同拍写穿透：有它读口答案取自写口，没有它读口给文件现值。
- CSR：地址随 uop 锁存（`exCsrAddr`），读是组合读，写只发生在提交拍；flush 不污染架构状态。
- 总线纯 Decoupled：取消/甄别全在 master 侧（取指用 epoch、LSU 用请求 tag）；reset 期间的流量拒绝是 fabric 的职责。
- 异常/中断：**不做**。`--flow verilator` 时每个计数器一个 `public_flat_rd` 黑盒（模块名即计数器名），名字由声明处的
  scope 拼成：`stat_core_middle_cycle` / `stat_core_middle_inst_commit`（Middle）与 `stat_core_back_int_bp_branch` /
  `stat_core_back_int_bp_mispred`（IntPath），派生 `ipc` / `mispredict_rate`，schema 随 dump 一起产出（见
  `stat-schema.md`）。

## 基线测量（2026-09-29）

- riscv32i / icsprout55 H7CL tt / 2 ns 约束：面积 20878.76，最差路径 1.190 ns（提交 `next_pc` 一端的锥：
  op 译码 → BRU 目标基址 mux → 分支目标加法器），等价 812.9 MHz；riscv64i：面积 41520.92，最差路径 1.285 ns（752.0 MHz）。
- IPC（microbench `test`）：riscv32im 0.3412 / riscv32i 0.3711 / riscv64im 0.3332（差异来自相邻依赖对多等一拍）。
- difftest：remu `--platform nzea --sim-opt target=core`，microbench `test` 10/10（32i / 32im / 64im），
  CSR 读写汇编回归 32im / 64im 通过。

## 挂载顺序（每步正交、独立验证）

1. 基线（无 BP in-order）→ 验证三段合同 ✅
2. 挂 BP：前端取指器加回 PHT/BTB/RAS（合同点在 `Ifu` 内部，前端边界不变）
3. 挂 OoO 中端：RAT+PRF+ROB+IQ 重写 `Middle`（前/后端不动；操作数槽位合同不变，槽位改由 PRF 读 + wakeup 产生）
4. 向量后端：后端扩展（`BackOut` / `MemPath` 的契约点）

## 待定问题

- in-order 中端是否需要一个极小退休缓冲来承载将来的精确异常（基线先"完成即提交"）。若做，它与 OoO 的 ROB 是否共享同一合同语义？
- store 完成语义：基线等 resp 才提交（保证 MMIO store 完成）。未来 OoO 的提交/访存顺序不同，"store 完成"如何在合同上统一表达？
- 标签位宽：`DbusUser.tagWidth`、`IbusUser.epochBits` 现为常量；重命名/多发射时这些宽度定义在哪（是否随 `CoreParams` 协商）？
- 操作数槽位是三个（store 刚好用满）；V 扩展 / FMA 这类三源以上的操作是加第 4 槽，还是让该后端自带私有槽位？
