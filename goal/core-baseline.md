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
    idu/                Idu（PLA 译码 + ARF 读 + 操作数槽位解析）/ inst（指令表）/ DecodeTables（真值表 → PLA）
  middle/               Middle（单飞行分发 + 提交，多态点）/ CsrFile / CommitMsg
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

- 前端→中端 `IduOut`（**架构级**）：`pc / imm / rd_index / src_a / src_b / src_c / backend / op 窗口 / csr_addr`。
  ARF 读与操作数槽位解析都在译码拍完成（`Srcs.resolve`），中端只锁存。
- 中端→后端 `IssueUop`（**就绪操作数**）：三个槽位 + `pc`/`imm`（结构用途：`pc+4`、分支偏移、BRU 目标基址）
  + `backend` + `op` 窗口 + `csr_type`/`csr_rdata`。后端不做源选择，只按自己的 op 读槽位：
  ALU 读 a/b；BRU 比较 a/b、JALR 用 a 当目标基址；SYSU 的写数据与"零源不写"判定读 a；MUL/DIV 读 a/b；
  LSU 地址 = a+b、store 数据 = c。
- 后端→中端 `BackOut`（完成报告）：`done / data / next_pc / csr_type / csr_data / is_mmio / flush / redirect_target`。
- 中端→前端：`redirect`（Valid，显式事件，同时是唯一让 `Ifu` 自增 epoch 的原因）+ `flush`（广播，给流水寄存器）。
- 后端窗口：`IntOp` = `fuOp | fuType`，`MemOp` = `LsuOp`；二者低锚定、互相别名，共享 `op` 字段的宽度 = `OpWindow.Width`。
- 中端→顶层 `commit`（`CommitMsg`）：架构状态变化的轨迹（`next_pc`/`rd_index`/`rd_value`/`is_mmio`/`csr_*`）。

## 基线行为

- 顺序单发射；取指永远 `pc+4`（always-not-taken），分支在 BRU 解析后 flush + redirect；无 BPU。
- 单飞行：同一时刻最多一条指令在途；操作数在译码拍读 ARF（post-commit 值），无 forwarding 路径。
- CSR：地址随指令锁存（`ex.csr_addr`），读是组合读，写只发生在提交拍；flush 不污染架构状态。
- 总线纯 Decoupled：取消/甄别全在 master 侧（取指用 epoch、LSU 用请求 tag）；reset 期间的流量拒绝是 fabric 的职责。
- 异常/中断：**不做**。`sim=true` 时经 `StatsRegs` 暴露 `stat_cycle` / `stat_inst_commit`（+ BRU 的
  `stat_bp_branch` / `stat_bp_mispred` / `stat_bp_actual_taken`），schema 随 dump 一起产出（见 `stat-schema.md`）。

## 基线测量（2026-09-27）

- riscv32i / icsprout55 H7CL tt / 2 ns 约束：面积 20279.00（时序单元 48.18%），最差路径 1.285 ns
  （取指 PC 自环：`pc` → `+4` 进位链 → redirect mux → `pc`），WNS +0.671 ns；紧随其后是 `back.flushReg`（1.261 ns）。
- difftest：remu `--platform nzea --sim-opt target=core`，microbench `test` 10/10。

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
