# Difftest Store Bug 分析报告

## 错误现象

```
mem 0x87ffff68:4 ref=0x00000088 dut=0xac600080
```

- **地址**: 0x87ffff68 = sp+8，对应 `sw s0, 8(sp)`
- **ref (正确)**: 0x00000088 = s0 的值
- **dut (错误)**: 0xac600080 = 小端序的 0x800060ac = jalr 的 pc+4 返回值 (ra)

## 波形定位

### 关键时间点

| 时间 | 事件 |
|------|------|
| t=72, t=100, t=150 | 对 0x87ffff68 的 store |
| t=150 | **错误发生**：wdata=0x800060ac (ra)，应为 0x00000088 (s0) |
| t=143 | sw s0 (pc=0x80006120) 在 IDU 中解码 |
| t=146 | sw s0 在 ISU 中，准备 dispatch 到 AGU |
| t=147-148 | AGU 接收 sw s0 |
| t=110, t=130 | **Flush 事件** |

### 数据流追踪

1. **t=150 AGU 输入**：
   - pc = 0x80006120 (sw s0, 8(sp))
   - storeData = 0x800060ac (错误！应为 s0=0x88)
   - base = 0x87ffff60, imm = 8 → addr = 0x87ffff68 ✓

2. **t=146 ISU 输入**（sw s0 在 ISU）：
   - p_rs2 = **000001 (PR1)** ← 错误！应为 PR9
   - p_rs1 = 000010 (PR2) ✓
   - PRF(1) = 0x800060ac (ra)
   - PRF(9) = 0 (s0 应在 PR9，但 RMT 错误地指向 PR1)

3. **t=143 RMT 状态**：
   - table_7 (s0) = **000001 (PR1)** ← 错误
   - table_8 (s1) = 001001 (PR9)
   - table_1 (ra) = 000010 (PR2)

## 根因分析

### 问题链

1. **RMT table_7 (s0) 错误地指向 PR1**
   - 正确应为：s0 → PR9（或 s0 实际映射的 PR）
   - 实际：s0 → PR1

2. **PR1 持有 ra 的值 (0x800060ac)**
   - ra 的指令写入了 PR1
   - 因此 PRF(PR1) = 0x800060ac

3. **sw s0 使用 p_rs2=PR1 读 PRF**
   - 得到 0x800060ac 而非 0x88

### Flush 与 Checkpoint 时序

Timeline 显示：
- **t=106**: commit rd=s0, p_rd=PR1 → table_cp_7 = PR1
- **t=124**: commit rd=a0, p_rd=PR2, **old_p_rd=PR1** → PR1 被 push 到 FreeList
- **t=130**: **flush**，commit rd=ra, p_rd=PR1

Flush 时恢复逻辑：
```scala
when(io.flush) {
  for (i <- 0 until 31) { table(i) := table_cp(i) }
  when(io.commit.valid && ...) {
    table(io.commit.bits.rd_index - 1.U) := io.commit.bits.p_rd
  }
}
```

- 恢复后：table_7 = table_cp_7 = **PR1**（来自 t=106，未更新）
- 同时：table_0 (ra) := PR1（flush 时的 commit）
- **结果**：ra 和 s0 都指向 PR1

### 根本原因

**RMT checkpoint (table_cp) 在 PR 被 free 后未正确更新**：

1. t=106: s0 commit → table_cp_7 = PR1
2. t=124: a0 commit, old_p_rd=PR1 → PR1 被 push 到 FreeList
3. **table_cp_7 仍为 PR1**（只更新了 table_cp_9）
4. PR1 被后续指令（写 ra）复用
5. t=130 flush 时：table_7 = table_cp_7 = PR1（陈旧）
6. 导致 s0 和 ra 都映射到 PR1，PR1 存的是 ra 的值

## 修复建议

### 方案 A：Free PR 时更新 table_cp

当 commit 时 push old_p_rd，需要将 table_cp 中所有指向 old_p_rd 的项更新，避免 checkpoint 指向已释放的 PR。

**问题**：简单地将 `table_cp(i) := p_rd` 当 `table_cp(i) === old_p_rd` 会错误地将 s0 指向 a0 的 PR，造成新的重叠。

### 方案 B：Checkpoint 一致性

确保 table_cp 与 FreeList 一致：若 PR 在 FreeList 中，则 table_cp 中不应有项指向它。需要在 free 时扫描 table_cp 并修正，但"修正为谁"需要额外信息。

### 方案 C：Flush 时使用正确的 checkpoint

Flush 时的 checkpoint 应对应于 flush 点的 architectural state。若当前实现中 table_cp 在每次 commit 时更新，则需确保 flush 时应用的 commit 与 table_cp 的更新顺序一致。

### 方案 D：深入调查重叠来源

在 t=106 与 t=124 之间，为何会出现 s0 和 a0 同时映射到 PR1？可能是：
- 某处 move 优化或特殊路径导致 old_p_rd == p_rd
- FreeList/RMT 的 checkpoint 与 working 状态不同步
- 与 ALLOC_BUG_ANALYSIS.md 中 old_p_rd == p_rd 的 bug 相关

## wave_tracker 使用命令

```bash
# 扫描 store 地址
cargo run --release -- -f /path/to/trace.fst --scan 0 --scan-end 50000 -g dbus_req_bits_addr --filter-value 87ffff68

# 查看 t=150 的 wdata/storeData
cargo run --release -- -f /path/to/trace.fst -t 150 -g wdata
cargo run --release -- -f /path/to/trace.fst -t 150 -g storeData

# 查看 RMT/PRF 状态
cargo run --release -- -f /path/to/trace.fst -t 143 -g rmt.table
cargo run --release -- -f /path/to/trace.fst -t 146 -g prf_regs

# Timeline 分析
cargo run --release -- -f /path/to/trace.fst --timeline 100 --timeline-end 160
```

## 总结

| 项目 | 结论 |
|------|------|
| 错误指令 | sw s0, 8(sp) @ 0x80006120 |
| 错误数据 | storeData=0x800060ac (ra) 而非 0x88 (s0) |
| 直接原因 | p_rs2=PR1，PRF(1)=0x800060ac |
| RMT 错误 | table_7(s0)=PR1，应为 PR9 或 s0 的正确 PR |
| 根本原因 | Flush 后 table_cp 恢复时，s0 仍指向已释放并被 ra 复用的 PR1 |
| 相关 commit | t=106 (s0→PR1), t=124 (a0 free PR1), t=130 (flush, ra→PR1) |
