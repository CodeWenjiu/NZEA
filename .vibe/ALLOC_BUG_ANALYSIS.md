# Allocation Bug Analysis: old_p_rd == p_rd at Commit

## Bug Summary

At t=108, commit has `rd_index=sp`, `p_rd=PR2`, `old_p_rd=PR2`. PR2 is both:
- Allocated from FreeList (as `p_rd`)
- Current sp mapping in RMT (as `old_p_rd`)

This violates the invariant: a PR is either in FreeList (free) or in RMT (in use), never both.

---

## 1. IDU.scala – needAlloc, canAlloc, p_rd, old_p_rd

### needAlloc
```scala
val needAlloc = rd_index =/= 0.U && fuType =/= FuType.SYSU
```
- SYSU instructions do **not** need allocation (rd_index forced to 0 in ISU).
- All other instructions with rd≠0 need allocation.

### canAlloc
```scala
val renameStall  = needAlloc && freeList.io.empty
val canAlloc     = canOutput && !renameStall && needAlloc
```

### p_rd
```scala
val p_rd = Mux(canAlloc, freeList.io.pr.bits, 0.U(prfAddrWidth.W))
```
- **p_rd comes from FreeList only when canAlloc.**
- When !needAlloc (e.g. SYSU): p_rd = 0.
- When needAlloc but !canAlloc (rename stall): p_rd = 0 (instruction stalls).

### old_p_rd
```scala
val old_p_rd = rmt.io.read(2).pr   // RMT lookup for rd_index
val old_p_rd_out = Mux(needAlloc, old_p_rd, 0.U(prfAddrWidth.W))
```
- **old_p_rd always comes from RMT** (read port 2 for rd).

### RMT write (rename)
```scala
rmt.io.write.valid := canAlloc
rmt.io.write.bits.ar := rd_index
rmt.io.write.bits.pr := p_rd
```
- RMT is updated only on allocation (rename write), not on commit.

---

## 2. ISU.scala – Passing to ROB

```scala
io.rob_enq.req.bits.p_rd := io.in.bits.p_rd
io.rob_enq.req.bits.old_p_rd := io.in.bits.old_p_rd
```
- ISU forwards IDU’s `p_rd` and `old_p_rd` to ROB without modification.

---

## 3. FreeList.scala – Pop/Push Logic

### Pop (allocation)
- `io.pop.valid := canAlloc`, `io.pop.bits := true.B`
- PR is taken from `buf(head)`; head advances when `pop.fire && head =/= tail`.

### Push (free on commit)
- Driven from IDU:
  ```scala
  freeList.io.push.valid := io.commit.valid && io.commit.bits.rd_index =/= 0.U && !io.flush
  freeList.io.push.bits  := io.commit.bits.old_p_rd
  ```
- A PR enters the FreeList when an instruction commits and its `old_p_rd` is pushed.

### Checkpoint (commit path)
```scala
when(io.commit.valid && io.commit.bits =/= 0.U) {
  buf_cp(tail_cp(...)) := io.commit.bits
  tail_cp := tail_cp + 1.U
}
```
- `freeList.io.commit.bits := io.commit.bits.old_p_rd` (from IDU).

---

## 4. RMT.scala – table/table_cp Updates

### Commit path (non-flush)
```scala
when(io.commit.valid && io.commit.bits.rd_index =/= 0.U) {
  table_cp(io.commit.bits.rd_index - 1.U) := io.commit.bits.p_rd
}
```
- **Only table_cp is updated on commit when !flush.**
- The live `table` is **not** updated on commit.

### Rename path
```scala
.elsewhen(io.write.valid && io.write.bits.ar =/= 0.U) {
  table(io.write.bits.ar - 1.U) := io.write.bits.pr
}
```
- Live `table` is updated only on rename write (allocation).

### Flush path
```scala
when(io.flush) {
  for (i <- 0 until 31) { table(i) := table_cp(i) }
  when(io.commit.valid && io.commit.bits.rd_index =/= 0.U) {
    table(io.commit.bits.rd_index - 1.U) := io.commit.bits.p_rd
  }
}
```
- On flush, `table` is restored from `table_cp` and then the commit is applied combinationally.

---

## 5. Move Optimization (addi rd, rs1, 0)

**No move optimization exists.**

- ADDI uses generic decode: `Fu.ALU(AluOp.Add, AluSrc.Rs1Imm)`.
- There is no special case for `addi rd, rs1, 0` or `rd == rs1`.
- `p_rd` is always from FreeList when `canAlloc`; there is no `p_rd := p_rs1` or `p_rd := old_p_rd`.

---

## Root Cause Analysis

For `old_p_rd == p_rd == PR2` at commit:

1. At decode time for that instruction:
   - `old_p_rd = RMT[sp] = PR2`
   - `p_rd = FreeList.pop = PR2`

2. So PR2 was both:
   - In RMT (sp’s mapping)
   - In FreeList (available for allocation)

3. That violates the invariant. A PR should be either in use (RMT) or free (FreeList).

4. When we commit with `old_p_rd == p_rd`:
   - We push `old_p_rd = PR2` to FreeList.
   - We set `RMT[sp] := p_rd = PR2` (no change).
   - We put a PR that is still in use into FreeList, creating a duplicate.

5. Later, another instruction can:
   - Pop PR2 from FreeList (p_rd = PR2)
   - Read RMT[sp] = PR2 (old_p_rd = PR2)
   - Again get `old_p_rd == p_rd`.

---

## Fix Location

**File:** `nzea_core/src/frontend/IDU.scala`

**Problem:** We push `old_p_rd` to FreeList even when `old_p_rd == p_rd`. In that case we are not freeing a PR; we are reusing the same one. Pushing it creates a duplicate and breaks the invariant.

**Fix:** Do not push when `old_p_rd == p_rd`:

```scala
// Main FreeList: push on commit when !flush
// Do NOT push when old_p_rd == p_rd: we're reusing the same PR, not freeing it.
freeList.io.push.valid := io.commit.valid && io.commit.bits.rd_index =/= 0.U && !io.flush &&
  io.commit.bits.old_p_rd =/= io.commit.bits.p_rd
freeList.io.push.bits  := io.commit.bits.old_p_rd

// Checkpoint: same guard
freeList.io.commit.valid := io.commit.valid && io.commit.bits.rd_index =/= 0.U &&
  io.commit.bits.old_p_rd =/= io.commit.bits.p_rd
freeList.io.commit.bits := io.commit.bits.old_p_rd
```

**Why this is safe:**
- When `old_p_rd == p_rd`, no PR is actually freed; the same PR is reused.
- Pushing would incorrectly add a PR that is still in use.
- Skipping the push keeps FreeList and RMT consistent.

**Note:** This fixes the symptom. The underlying cause of `old_p_rd == p_rd` (e.g. move optimization, flush/restore race, or other bug) may still need investigation. If you add a move optimization (e.g. `addi rd, rs1, 0` with `rd == rs1` reusing `p_rs1`), you must ensure we never push in that case; this guard does that.

---

## Code Path Summary

| Stage   | p_rd source              | old_p_rd source |
|---------|---------------------------|-----------------|
| IDU     | FreeList.pop (when canAlloc) | RMT.read(rd)  |
| ISU     | Pass-through from IDU      | Pass-through from IDU |
| ROB     | Stored in slots_p_rd      | Stored in slots_old_p_rd |
| Commit  | From ROB head             | From ROB head   |
| IDU     | Used for RMT checkpoint    | Pushed to FreeList |

**Invariant:** `old_p_rd` and `p_rd` should differ whenever we allocate a new PR. When they are equal, we are reusing a PR and must not push it to FreeList.
