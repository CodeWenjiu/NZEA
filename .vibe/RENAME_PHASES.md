# 寄存器重命名实现分步计划

## 配置

- `prfDepth`: 物理寄存器堆深度，默认 64。前 32 个 (PR0~PR31) 初始映射给 AR0~AR31，后 32 个 (PR32~PR63) 在 Free List 中。

---

## Phase 1: 基础设施（无流水线改动）✓

- [x] NzeaConfig 增加 `prfDepth: Int = 64`
- [x] 新建 `PRF` 模块：存储 + Ready 位，读/写端口
- [x] 新建 `RMT` 模块：32 项映射表，支持 checkpoint
- [x] 新建 `FreeList` 模块：环形缓冲，支持 checkpoint
- [x] 验证编译通过

---

## Phase 2: IDU 中的 Rename 逻辑 ✓

- [x] IDU 实例化 RMT、FreeList（内置 checkpoint）
- [x] Rename：查 RMT 得 p_rs1/p_rs2，从 FreeList pop 得 p_rd，记录 old_p_rd
- [x] IDUOut 增加 p_rs1, p_rs2, p_rd, old_p_rd
- [x] 分支 dispatch 时做 checkpoint 快照
- [x] 增加 commit 输入：old_p_rd 用于 FreeList push
- [x] 保持原有 rs1_index/rs2_index 输出（兼容过渡）
- [x] RobEnqPayload/CommitMsg 增加 old_p_rd，ROB 存储并输出

---

## Phase 3: PRF 与 ISU 读路径 ✓

- [x] Core 实例化 PRF，连接 ISU 读端口、Rob prf_write
- [x] ISU 接收 p_rs1/p_rs2，从 PRF 读数据
- [x] ISU 用 PRF_Ready 判断 stall，移除 GPR/slot 读逻辑
- [x] ROB enq 传递 p_rd, old_p_rd
- [x] 分配时 PRF clearReady[p_rd]

---

## Phase 4: ROB、Commit 与写回 ✓

- [x] ROB 每 entry 存储 p_rd, old_p_rd
- [x] FU 完成时写 PRF(p_rd) 并置 PRF_Ready[p_rd] := true
- [x] Commit 时把 old_p_rd 送回 IDU 做 FreeList push
- [x] CommitMsg.rd_value 从 ROB 读（保留 ROB 冗余）
- [x] 移除 ISU 中的 RAT 和 rat_rob_write

---

## Phase 5: Flush 恢复 ✓

- [x] do_flush 时：RMT_working := RMT_checkpoint，FreeList_working := FreeList_checkpoint
- [x] 确保 flush 信号正确传递到 IDU（Rob.do_flush 直连 IDU.flush，与流水线传播双路保证）
