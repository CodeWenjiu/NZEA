# 死锁分析报告 (t≈292980)

## 死锁位置

**卡在发射队列 (IssueQueue)**，不是 ROB 满，也不是 LS 队列满。

## 波形状态摘要

| 组件 | 状态 |
|------|------|
| **IQ** | count=3, valids_0,1,2=1, **canIssue 全 0** |
| **ROB** | head=slot 2, 该槽 is_done=0 |
| **LS 队列** | head=slot 3, data_ready=0 (AGU 未写入) |
| **IQ 条目** | 3 条均 rs1/rs2_ready=0，全部在等操作数 |

## 死锁链

1. **ROB 无法 commit**：head 在 slot 2，该指令未完成
2. **head 指令在 IQ**：rob_id=2 的 addi 在 IQ entry 0，rs1_ready=0
3. **IQ 无法发射**：3 条指令都缺操作数，canIssue 全 0
4. **LS 队列 head 无法推进**：rob_id=4 的 load 在 IQ entry 2，rs1/rs2_ready=0，AGU 收不到该 load，无法写 data_ready

## 根本原因：IQ 未读 PRF

- IQ 的 bypass 只来自 `prf_write` 和 `bypass_level1`（本周期写回）
- 若生产者**多周期前**已完成，值已在 PRF，但 IQ 不会重新读
- IQ 只保留入队时的 `e.rs1_ready`，入队时若生产者未完成则为 0
- 之后生产者完成并写入 PRF，IQ 仍用旧的 `e.rs1_ready=0`，永远不更新

## 解决方案

**方案 A（推荐）：恢复 ISU 的操作数 stall**

- 仅在 `rs1_ready && rs2_ready` 时允许 push
- 保证入队时操作数已就绪，IQ 不再依赖“后续 PRF 更新”
- 实现简单，与当前设计兼容

**方案 B：为 IQ 增加 PRF 读口**

- 每个 IQ 条目对 rs1/rs2 做 PRF 读
- 需要较多 PRF 读口，面积和时序成本高
