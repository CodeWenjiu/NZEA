# 死锁根因分析报告

## 问题概述

产生 PR37 的指令（rob_id=7）在 ROB slot 7 中 `is_done=false`，导致依赖 PR37 的指令（entry0: ALU）无法执行，进而导致 entry1（AGU）无法获得 PR34，形成死锁。

## 波形追踪结果

### 1. 指令入队 (t=112-113)

```
t=112 *** ROB_ENQ rob_id=0111 p_rd=100101 IQ_IN=true
t=113 *** ROB_ENQ rob_id=0111 p_rd=100101 IQ_IN=true
```
- 指令同时进入 ROB 和 IQ，无 ROB-IQ 不同步

### 2. **关键发现：LSQ 未分配**

```
t=112 DISPATCH: ROB_ENQ=true IQ_IN=true LS_ALLOC=false (valid=false ready=true) fu_type=000 lsq_id=101
t=113 DISPATCH: ROB_ENQ=true IQ_IN=true LS_ALLOC=false (valid=false ready=true) fu_type=000 lsq_id=101
```

**LS_ALLOC 未 fire**：指令进入 ROB 和 IQ，但 **未进入 LSQ**。

- `ls_alloc.valid=false`：ISU 未拉高 ls_alloc
- `ls_alloc.ready=true`：MemUnit 可接收分配
- `fu_type=000`：在 ISU 阶段被识别为 ALU（0），而非 LSU（2=010）

### 3. 矛盾点

- 该指令在 t=114-115 被 **发射到 AGU**（`ISSUE_AGU`），说明 IQ 将其视为 load/store
- IQ entry1 中 `fu_type=000`、`lsq_id=000`，与 LSU 不符
- 若为 LSU 指令，则应在 dispatch 时执行 `ls_alloc`，分配 LSQ 槽位

### 4. 根因推断

**指令在 ISU 阶段未被正确识别为 LSU**，导致：

1. `ls_alloc.valid` 未拉高，LSQ 未分配
2. 指令仍携带 `lsq_id=0`（非 LSU 时 `io.out.bits.lsq_id := 0.U`）
3. AGU 执行后通过 `ls_write` 写 `ls_slots(0)`，但该槽位可能未为该指令分配
4. 导致 load 无法正确完成，`is_done` 一直为 false

**可能原因**：
- IDU/ISU 中 fu_type 解码错误，将 load/store 识别为 ALU
- 或 fu_type 在 ISU→IQ 传递过程中被错误覆盖

### 5. 建议排查方向

1. **检查 IDU 解码**：该 load 指令的 fu_type 在 IDU 输出是否为 LSU（010）
2. **检查 ISU 的 fu_type 来源**：`fu_type` 是否来自 `io.in.bits`，以及 IDU→ISU 流水线是否引入错误
3. **确认 ls_alloc 条件**：`io.ls_alloc.valid := can_push && io.out.ready && (fu_type === FuType.LSU)` 中 fu_type 是否正确

## 新增波形分析工具

- `--trace-rob-id 0111`：追踪 rob_id 7 在流水线中的完整轨迹
- `--trace-p-rd 100101`：追踪产生 PR37 的指令
- `--enq-match 0111,100101`：查找 rob_id=7 且 p_rd=PR37 的入队时刻
- `--dispatch-lsq 0111,100101`：检查 ROB+IQ+LSQ 是否同步分配，并输出 IQ entry 的 fu_type/lsq_id
- `--deadlock-tail N`：输出最后 N 周期的 IQ 状态（含 rob_id）
