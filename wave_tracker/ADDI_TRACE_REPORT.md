# Addi sp, sp, -0x10 Trace Report (0x80006118)

**Correction:** sp is x2 (rd_index=00010), not x1.

**Difftest error:** ref=0x87ffff60, dut=0x87ffff68 (dut 8 more)

## Addi Commit (Corrected)

**Time:** t=144, idx=145

| Field | Value |
|-------|-------|
| rd_index | 00010 (x2 = sp) |
| rd_value | 0x87ffff68 (wrong - should be 0x87ffff60) |
| next_pc | 0x8000611C (addi+4) ✓ |
| p_rd | 000010 (PR2) |
| old_p_rd | 000010 (PR2) |

**ROB head:** slot 0, slots_rd_value_0=0x87ffff68

**Source of wrong value:** ALU at t=142 wrote 0x87ffff68 to PR2. ALU operands at t=142: opA=0x87ffff78 (wrong sp), opB=-16. The addi correctly computes opA+opB = 0x87ffff68. **Root cause: opA (sp) was 0x87ffff78 instead of 0x87ffff70** — PR2 had wrong value.

**PRF write at t=122:** ALU wrote 0x87ffff78 to PR2 (addr 000010). Instruction in slot 0: rd_index=01010 (x10), p_rd=PR2. So x10's value overwrote PR2. RMT table_1 (sp) maps to PR2 at addi dispatch — sp and x10 both ended up mapping to PR2, or PR2 was reused before sp's consumer read it.

## 1. PRF/Reg File Signals

| Signal | Description |
|--------|-------------|
| `TOP.Top.core.isu.prf_regs_0` .. `prf_regs_63` | Physical register file (64 entries) |
| `TOP.Top.core.isu.prf_ready_0` .. `prf_ready_63` | PRF ready bits |
| `TOP.Top.core.isu.io_prf_write_0..3_valid/bits_addr/bits_data` | PRF write ports (ALU, BRU, SYSU, MemUnit) |

## 2. Commit Info at t=130 (idx=131)

| Field | Value (binary) | Hex/Dec |
|-------|----------------|---------|
| rd_index | 00001 | x1 (sp) |
| **rd_value** | 10000000000000000110000010101100 | **0x800060AC** (WRONG) |
| p_rd | 000001 | PR1 |
| old_p_rd | 100000 | PR32 |
| next_pc | 10000000000000000110000100011000 | 0x80006118 |

**Bug:** rd_value=0x800060AC is sent to difftest; should be 0x87ffff60. 0x800060AC = 0x800060A8+4 (looks like BRU pc+4).

## 3. Addi Dispatch

**First dispatch (before flush):** t=106
- `isu.io_in_bits_pc`: 0x80006118
- `p_rs1`: 000010 (PR2)
- `p_rd`: 100000 (PR32), `old_p_rd`: 000010 (PR2)
- PRF(2) = 0x87ffff70 ✓ (correct sp)

**After flush, addi in ALU:** t=112
- `alu.io_in_bits_opA`: 0x87ffff70 ✓
- `alu.io_in_bits_opB`: 0xFFFFFFF0 (-16) ✓
- ALU correctly computes 0x87ffff60 at t=108 (first dispatch) and t=112 (second)

## 4. Flush Timeline

| t | do_flush |
|---|----------|
| 20 | 1 |
| 52 | 1 |
| **110** | **1** (last before addi commit) |
| 130 | 1 (same cycle as addi commit) |

At t=110: head=slot 2 (mispredicted branch), slot 3 has addi with rd_value=0x87ffff60 ✓. Flush clears ROB.

## 5. RMT table_0 (sp) and table_cp_0

| t | table_0 | table_cp_0 |
|---|---------|------------|
| 125-127 | 000001 (1) | 100011 (35) |
| 128 | 100010 (34) | 100000 (32) |
| 129 | 100010 (34) | 100000 (32) |
| 130 | 100001 (1) | 100000 (32) |

At t=128 (flush): table≠table_cp; after flush sp should restore to table_cp_0=32.

## 6. Root Cause: BRU Overwrites Addi's rd_value

- At t=128: **BRU writes valid=1, addr=PR1, data=0x800060AC** (pc+4 from 0x800060A8)
- At t=130: head=slot 2, slots_p_rd_2=000001 (PR1), slots_rd_value_2=0x800060AC
- The addi allocates PR1 (p_rd=1) when dispatched after flush. The BRU (JAL at 0x800060A8) also targets PR1 and writes 0x800060AC.
- **Conclusion:** The instruction in slot 2 at t=130 has p_rd=1 and rd_value=0x800060AC. Either (a) the addi's rd_value was overwritten by a BRU write to the same ROB slot, or (b) slot 2 holds a different instruction (JAL) that incorrectly got the addi's rd_index (sp). The wrong value 0x800060AC is a BRU-style pc+4 result.

## 7. PRF Write from Flushed Instruction?

At t=108: ALU writes PR32 with 0x87ffff60 ✓ (addi result). At t=110 flush clears ROB but PRF is NOT cleared. PR32 keeps 0x87ffff60. After flush, RMT restores sp→PR32. So PRF(32) has correct value. The bug is in **commit rd_value** (from ROB), not PRF.
