# Exact Timeline: old_p_rd == p_rd at t=108

**Conclusion:** Commits are IN-ORDER (ROB head commits each cycle). The "out-of-order commit" hypothesis is wrong. The bug state (PR2 in FreeList AND RMT(sp)=PR2) was created by an **incorrect push** when old_p_rd==p_rd at t=66. The t=108 instruction dispatched at t=98 when that bug state already existed.

---

## 1. ROB Head/Tail at Each Cycle

**At t=108:**
- `head_ptr` = 0001 (slot 1)
- `tail_ptr` = 0100 (slot 4)
- The instruction that commits at t=108 was in **ROB slot 1**
- It was **enqueued (dispatched) at t=98** when tail was 1

**Commits are strictly in-order:** Only the ROB head commits each cycle. No out-of-order commit.

---

## 2. Dispatch Order vs Commit Order

Instructions that commit between t=10 and t=108 (in commit order = dispatch order):

| t (commit) | rd_index | p_rd | old_p_rd | Effect |
|------------|----------|------|----------|--------|
| 10 | sp (00010) | PR32 | PR2 | push PR2 |
| 14 | sp | PR33 | PR32 | push PR32 |
| 16 | ra (00001) | PR34 | PR1 | push PR1 |
| 20 | ra | PR35 | PR34 | push PR34 |
| 34 | sp | **PR2** | PR33 | push PR33 |
| 44-47 | x0 | 0 | 0 | reuse |
| 48 | x8 | PR32 | PR8 | push PR8 |
| 50 | ra | PR1 | PR35 | push PR35 |
| 52 | ra | PR34 | PR1 | push PR1 |
| **66** | **sp** | **PR2** | **PR2** | **reuse (no push)** ← Bug: if old code pushed, PR2 enters FreeList |
| 76-79 | x0 | 0 | 0 | reuse |
| 80 | x8 | PR32 | PR32 | reuse |
| 82 | x10 | PR1 | PR10 | push PR10 |
| 84 | x11 | PR34 | PR11 | push PR11 |
| 86 | x12 | PR33 | PR12 | push PR12 |
| 88-103 | x0 | 0 | 0 | reuse |
| 104 | ra | PR35 | PR34 | push PR34 |
| 106 | x8 | PR1 | PR32 | push PR32 |
| **108** | **sp** | **PR2** | **PR2** | **reuse (no push)** |

**Verification:** Commit order matches dispatch order (FIFO from ROB head).

---

## 3. The Instruction that Commits at t=108

| Field | Value |
|-------|-------|
| **Dispatch cycle** | **t=98** |
| rd_index | 00010 (sp) |
| p_rd | 000010 (PR2) |
| old_p_rd | 000010 (PR2) |
| rob_id | 001 (slot 1) |

**At dispatch (t=98):**
- Read `old_p_rd` from RMT(sp) → **RMT(sp) = PR2**
- Pop `p_rd` from FreeList → **FreeList head = PR2**
- Result: old_p_rd == p_rd == PR2

**FreeList at t=97 (before the t=98 pop):**
- head = 001000 (8)
- tail = 101110 (46)
- **buf[8] = 000010 (PR2)** ← PR2 at FreeList head
- io_pop_valid = 1, io_pr_bits = 000010 (PR2)

**RMT table_1(sp) at t=98:** 000010 (PR2)

So at dispatch: **FreeList head = PR2** and **RMT(sp) = PR2**. The instruction popped PR2 and read RMT(sp)=PR2 → old_p_rd == p_rd.

---

## 4. When Did PR2 Enter FreeList?

PR2 is pushed when we commit with `old_p_rd=PR2` and `old_p_rd ≠ p_rd`.

**Commits with old_p_rd=PR2:**
- **t=10:** push PR2 (correct: old_p_rd=PR2, p_rd=PR32)
- **t=66:** old_p_rd=PR2, p_rd=PR2 → **reuse, no push** (correct with fix)
- **t=108:** same

**Root cause:** At t=66, we had old_p_rd == p_rd == PR2. The **old (buggy) code** would push old_p_rd=PR2 even when old_p_rd==p_rd. That incorrectly puts PR2 into FreeList while RMT(sp)=PR2. So we get:
- PR2 in FreeList (from the bad push)
- RMT(sp) = PR2 (from the commit)

**Timeline:**
1. **t=66:** Commit rd=sp, p_rd=old_p_rd=PR2. **Buggy code pushes PR2** → PR2 enters FreeList. RMT(sp)=PR2. **Bug state created.**
2. **t=98:** Instruction dispatches. FreeList head=PR2 (from t=66 push), RMT(sp)=PR2. Pop PR2 → p_rd=PR2, old_p_rd=PR2.
3. **t=108:** Commit. old_p_rd==p_rd=PR2. With fix: no push. Correct.

**Fix:** Do NOT push when old_p_rd == p_rd. This prevents the duplicate PR2 in FreeList.

---

## 5. Summary

| Question | Answer |
|----------|--------|
| ROB head at t=108? | Slot 1 |
| When was t=108 instruction dispatched? | **t=98** |
| FreeList head at dispatch? | PR2 (buf[8]=000010 at t=97) |
| RMT(sp) at dispatch? | PR2 |
| When did PR2 enter FreeList? | **t=66** (incorrect push when old_p_rd==p_rd) |
| Out-of-order commit? | **No** – commits are in-order |
