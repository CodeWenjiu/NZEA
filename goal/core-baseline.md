# 核心基线：最简 in-order 核

Status: **基线已落地** — nzea_core 已完成瘦身：OoO/BPU/向量/NNU/retire 结构全部移除，当前只有三段式
in-order 基线（`Core` = Front + Middle + Back），后续微架构按新思路逐步加回。

## 目标形态（三段）

```
前端 = 取指 + 译码（+BP 插件，基线没有）
中端 = 分发/重排 + 提交（顺序端）    ← 多态点：in-order 中端 vs 未来 OoO 中端
后端 = 执行 + 写回
```

- in-order 是同一合同的退化实现：ARF 当 PRF，无 RAT/ROB，提交=顺序完成。

## 当前结构（瘦身后）

```
nzea_core/src/
  Core.scala            三段装配 + 外部 IO（ibus/dbus/commit_msg）
  CoreInterconnect.scala  统一 CoreBus：通道工厂 + 阶段配对
  CoreEngine.scala       CoreLike 契约 + 宽度单一来源（CoreTagWidths）
  CommitMsg.scala        提交消息
  front/                Front（装配）
    ifu/                Ifu / IbusUser
    idu/                Idu / inst(decode tables)
  middle/               Middle（单飞行分发 + 提交，多态点）/ CsrFile
  back/                 Back（路径分发 + 完成合并 + 出口锁存）/ ExecPath（路径端口契约 + FuType 路由表）/ FuCompletion / PrfWriteBundle
    int/                IntPath（ALU/BRU/MUL/DIV/SYSU 的驱动与完成合并）/ ALU / BRU / MUL / DIV / SYSU
    mem/                MemPath（LSU：请求保持 + 响应 tag 匹配）/ MemUnit
  isa/                  跨段 ISA 级类型：FuType / Fu（描述符）/ 各 FU 操作码 / CsrType / FuOpWidth
  dpi/                  ibus/dbus/commit 的 DPI 桥
```

- 合同：前端→中端 `IduOut`（架构级）；中端→后端 `IssueUop`（就绪操作数）；
  后端→中端 `BackOut`（完成报告）；中端→前端 `Redirect`（显式事件）+ flush 链。
- 路径合同：`ExecPathPort`（issue/flush 入，done/result/redirect 出）；`ExecPath` 路由表强制每个
  `FuType` 归属唯一路径（未登记即 elaborate 期报错）。调度器将来插在端口之后、FU 之前。
- FU 完成契约：`FuCompletion.report(...)`（`tag` 回显、`is_done`、`flush`、`next_pc`、CSR 字段）。

## 基线行为

- 顺序单发射；取指永远 `pc+4`（always-not-taken），分支在 BRU 解析后 flush + redirect，无 BPU。
- 单飞行：同一时刻最多一条指令在途；操作数在译码拍读 ARF（post-commit 值），无 forwarding 路径。
- CSR 写只发生在提交拍；flush 不污染架构状态。
- 总线为纯 Decoupled：取消/甄别全部在 master 侧（epoch + LSU 请求 tag）；reset 期间的流量拒绝是 fabric 的职责。
- 异常/中断：**不做**。`sim=true` 时经 StatsRegs 暴露 `stat_cycle`/`stat_inst_commit`（remu `stat ipc` 消费）。

## 挂载顺序（每步正交、独立验证）

1. 基线（无 BP in-order）→ 验证三段合同 ✅
2. 挂 BP：前端取指器加回 PHT/BTB/RAS（合同点在 `Ifu` 内部，前端边界不变）
3. 挂 OoO 中端：RAT+PRF+ROB+IQ 重写 `Middle`（前/后端不动）
4. 向量后端：后端扩展（`FuCompletion`/`MemRequest` 契约点）

## 待定问题

- in-order 中端是否需要一个极小退休缓冲来承载将来的精确异常（基线先
  "完成即提交"）。若做，它与 OoO 的 ROB 是否共享同一合同语义？
- store 完成语义：基线等 resp 才提交（保证 MMIO store 完成）。未来 OoO 的
  提交/访存顺序语义不同，合同上"store 完成"如何统一表达？
- 标签位宽（`CoreTagWidths`）现为常量（lsuTag=2、regIndex=1）；未来重命名/多发射时
  这些宽度应移到何处定义？