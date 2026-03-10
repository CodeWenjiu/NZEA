# PR2 FreeList/RMT Bug Trace Report

## Summary

**Bug:** PR2 is both in FreeList (available to allocate) AND RMT(sp)=PR2 (sp maps to PR2) at the same time. This violates the invariant: a physical register should be either allocated (mapped in RMT) or free (in FreeList), never both.

**Bug window:** t=30 through t=149+ (continuous). The bug scan `--bug-scan 0 --bug-scan-end 150` finds PR2 in FreeList buf AND table_1(sp)=PR2 at every cycle in this range.

## 1. Instruction that Commits at t=108

| Field | Value |
|-------|-------|
| rd_index | 00010 (sp) |
| p_rd | 000010 (PR2) |
| old_p_rd | 000010 (PR2) |

**At dispatch:** FreeList popped PR2, RMT(sp)=PR2. Since p_rd=old_p_rd, the instruction reused PR2 (popped it from FreeList; RMT(sp) was already PR2). At commit, we correctly do NOT push (old_p_rd==p_rd).

**When dispatched:** The instruction was dispatched when the bug state already existed: PR2 was in FreeList (at head) and RMT(sp)=PR2. So we popped PR2 and got p_rd=PR2, old_p_rd=RMT(sp)=PR2.

## 2. Last Commit with old_p_rd=PR2 (that pushed PR2)

Scan for commits with old_p_rd=000010 (PR2):

| t | commit_valid | rd_index | p_rd | old_p_rd | Effect |
|---|-------------|----------|------|----------|--------|
| **10** | 1 | 00010 (sp) | 100000 (PR32) | 000010 (PR2) | **Pushed PR2** to FreeList. Set table_cp(sp)=PR32. After this, RMT(sp) should be PR32. |
| 34 | 1 | 00010 (sp) | 000010 (PR2) | 100001 (PR33) | Set sp→PR2. Pushed PR33 (not PR2). |
| **66** | 1 | 00010 (sp) | 000010 (PR2) | 000010 (PR2) | Reused PR2. Did NOT push. |
| **108** | 1 | 00010 (sp) | 000010 (PR2) | 000010 (PR2) | Reused PR2. Did NOT push. |

**Key:** At t=10, we pushed PR2 (freed it) and set table_cp(sp)=PR32. So after t=10, RMT(sp) should be PR32. The bug (PR2 in FreeList AND RMT(sp)=PR2) appears at t=30. So between t=10 and t=30, something set RMT(sp) back to PR2.

## 3. Flush Timeline

| t | do_flush |
|---|----------|
| 20 | 1 |
| 52 | 1 |
| 110 | 1 |
| 130 | 1 |

At t=20 flush: we restore RMT from table_cp. After t=10, table_cp(sp)=PR32. So restore should give table_1=PR32. So we should NOT have table_1=PR2 after t=20 flush.

**At t=34:** Commit sets sp→PR2 (p_rd=PR2). So we explicitly set table_cp(sp)=PR2. After t=34, RMT(sp)=PR2. And PR2 was pushed at t=10, so PR2 is in FreeList. **Bug introduced at t=34:** we set sp→PR2, but PR2 was already in FreeList (from t=10). The instruction that commits at t=34 had popped PR2 when dispatched—so we allocated PR2. But the bug scan checks raw buf slots; some may be in the "freed" region (before head). The valid FreeList range is [head, tail). If PR2 appears in buf_0, buf_8, etc., we need to verify those are in the valid range.

## 4. Bug Scan Results

```bash
cargo run --release -- --bug-scan 0 --bug-scan-end 150
```

**Cycles where PR2 in FreeList AND table_1(sp)=PR2:** t=30 through t=149+.

At t=50: buf_0=000010, buf_cp_0=000010, table_1=000010.
At t=68: buf_0, buf_8, buf_cp_0, buf_cp_8 all contain 000010.
At t=108: buf_0, buf_8, buf_16, etc. contain 000010.

**Note:** The bug scan checks if any buf/buf_cp slot equals PR2. It does not filter by FreeList head/tail (valid range). A slot before head may still hold a stale value. The invariant violation is: if PR2 is in the *valid* FreeList range and RMT(sp)=PR2, that's the bug.

## 5. Root Cause Hypothesis

**Scenario:** Out-of-order commit creates the bug.

1. Instruction A (writes sp) is dispatched, pops PR2, sets RMT(sp)=PR2.
2. Instruction B (writes X, X≠sp) had old_p_rd=PR2 (X was mapped to PR2).
3. Instruction B commits **before** A. We push old_p_rd=PR2 (free PR2). We set table_cp(X)=p_rd. We do NOT update table_cp(sp).
4. RMT(sp)=PR2 (from A's dispatch, which hasn't committed yet).
5. **Bug:** PR2 is in FreeList (we just pushed it) AND RMT(sp)=PR2.

So we free a PR that is still mapped in RMT by a younger (not yet committed) instruction. The fix would be: do not push old_p_rd if it is still mapped in the current RMT (from any in-flight dispatch). Or: ensure the rename/commit logic maintains the invariant that we never free a PR that is still mapped.

## 6. Wave Tracker Usage

```bash
# Bug scan: find cycles where PR is in FreeList AND RMT(sp)=PR
cargo run --release -- --bug-scan 0 --bug-scan-end 150
cargo run --release -- --bug-scan 0 --bug-scan-end 150 --bug-pr 000010

# Scan commits with PR2
cargo run --release -- --scan 0 --scan-end 120 -g "idu.io_commit" --filter-value "000010"

# Values at specific time
cargo run --release -- -t 108 -g "commit"
cargo run --release -- -t 108 -g "freeList.buf"
cargo run --release -- -t 108 -g "rmt.table_1"
```

## 7. Exact Sequence for t=108 Instruction

1. **t=10:** Commit rd=sp, p_rd=PR32, old_p_rd=PR2. Push PR2 to FreeList. Set table_cp(sp)=PR32.
2. **t=20:** Flush. Restore RMT from table_cp. table_1=PR32.
3. **t=34:** Commit rd=sp, p_rd=PR2, old_p_rd=PR33. Set table_cp(sp)=PR2. Push PR33. So RMT(sp)=PR2. PR2 was in FreeList (from t=10); the instruction that commits at t=34 had popped PR2 when dispatched, so at t=34 we've consumed it. But the bug scan shows PR2 in buf at t=34—possibly in invalid slots or from buf_cp.
4. **t=66:** Commit rd=sp, p_rd=old_p_rd=PR2. Reuse. No push.
5. **t=108:** Commit rd=sp, p_rd=old_p_rd=PR2. Reuse. No push.

**Dispatch of t=108 instruction:** Occurred when PR2 was at FreeList head and RMT(sp)=PR2. We popped PR2, got p_rd=PR2, old_p_rd=PR2. The bug state existed at that dispatch cycle.
