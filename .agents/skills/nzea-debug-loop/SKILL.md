---
name: nzea-debug-loop
description: Five-step debugging cycle for nzea RTL bugs — simulate with remu, inspect waveforms with wavepeek, write a minimal testbench to reproduce, fix the RTL, then re-simulate to confirm. Use when a simulation fails, difftest mismatches, or performance anomalies are observed.
---

# nzea Debug Loop

Use this skill when a simulation (remu + nzea DUT) fails, produces a difftest
mismatch, or shows suspicious performance behavior that needs root-cause
analysis and a fix.

The debugging cycle is **simulate → observe → reproduce → fix → verify**.
Each step has a clear entry condition, exit gating, and a concrete deliverable
before moving to the next.

## Step 1 — Simulate (capture the symptom)

**Goal**: Run the failing workload under remu + difftest and collect both
statistics and a waveform dump.

### Entry
Any of: `GOOD EXIT` with low IPC, difftest mismatch, panic / bus error, or
noticed regression in cycle-count reports.

### Execution

Load the `remu` skill for exact CLI invocation. The canonical invocation for
tile-level simulation with waveform and statistics:

```sh
just run-app microbench riscv32im --platform remu --app-args test \
  -- --batch --difftest remu --platform nzea \
  --sim-opt "target=tile watchdog=5" \
  --startup '{' step 10000 '}' and \
            '{' func trace wave-form on '}' and \
            '{' step 50000 '}' and \
            '{' stat print '}'
```

The startup script reproduces the bug under controlled observation:
1. `step 10000` — warm up caches (waveform off, avoiding cold-start noise)
2. `func trace wave-form on` — enable waveform
3. `step 50000` — observed window (waveform captured)
4. `stat print` — numeric evidence (IPC, hit/miss counts, cycle count)

The waveform lands at `../remu/target/trace.fst`. Record the path, the IPC,
and any error messages.

### Exit gating
- [ ] Waveform file exists and covers the full observation window
- [ ] Numeric evidence captured (IPC, stats from `stat print`)
- [ ] Failure symptom precisely noted (e.g. "IPC 0.10 at lineBits=32" or
  "difftest mismatch at PC=0x80005f44, regs t1/a1 differ")

**Do NOT skip to conclusions.** Move to Step 2.

---

## Step 2 — Observe (find the root event)

**Goal**: Use wavepeek to drill into the waveform and locate the exact cycle
and signal state that constitutes the bug.

### Entry
Waveform path from Step 1, plus a hypothesis about where to look (cache,
crossbar, LSU, etc.).

### Execution

Load the `wavepeek` skill for command syntax. Typical discovery sequence:

1. **Dump metadata**:
   ```sh
   wavepeek info --waves <FILE> --json
   ```

2. **Scope discovery** (find the relevant module):
   ```sh
   wavepeek scope --waves <FILE> --filter '.*Cache.*|.*crossbar.*' --max 30 --json
   ```

3. **Signal discovery** inside the target scope:
   ```sh
   wavepeek signal --waves <FILE> --scope <SCOPE> --max 60 --json
   ```

4. **Count events** (requests, misses, responses, writes):
   ```sh
   wavepeek property \
     --waves <FILE> --scope <SCOPE> --from <START> --to <END> \
     --on "posedge clock" \
     --eval "<VALID && READY>" --capture match \
     --max 200000 --json
   ```
   Run this for request fires, miss fires, and response fires separately.
   Compare counts: any discrepancy is a lead (e.g. req count ≠ resp count).

5. **Trace state transitions** to pinpoint the offending cycle:
   ```sh
   wavepeek change \
     --waves <FILE> --scope <SCOPE> --from <START> --to <END> \
     --on "posedge clock" \
     --signals <KEY_SIGNALS> \
     --max <N> --json
   ```

### Exit gating
- [ ] A precise description of the root event: "At time T, signal X has
  value V when it should have value W because condition C"
- [ ] Counts that quantify the scope: "34446 refill req fires vs 31899
  resp fires → 2557 orphan refills"
- [ ] The module (and ideally the line of Scala source) responsible

---

## Step 3 — Reproduce (write a testbench)

**Goal**: Encode the root event discovered in Step 2 into a minimal Chisel
testbench that **fails** on the current code.

### Entry
Root event description from Step 2, plus knowledge of which module(s) need
to be instantiated.

### Execution

1. Place the testbench in the module's test source directory:
   `<module>/test/src/<DescriptiveName>Test.scala`

2. If the module does not have a `test` sub-module in `build.mill`, add one
   following the pattern of `nzea_rtl` or `nzea_core`:
   ```scala
   object test extends ScalaTests with TestModule.ScalaTest {
     def mvnDeps = Seq(mvn"org.scalatest::scalatest:$scalatestVersion")
     def forkEnv = Map("PATH" -> sys.env.getOrElse("PATH", ""))
   }
   ```

3. Write a DUT wrapper module that instantiates the target module and provides
   a fake bottom bus (accept req instantly, reply with configurable delay) and
   observation outputs (counters, state signals).

4. The test must:
   - Drive the exact scenario from Step 2
   - Assert the **correct** behavior (which the bug violates)
   - **Fail** when run against the current (buggy) code

### Exit gating
- [ ] Test compiles: `mill <module>.test.compile`
- [ ] Test **fails** with a clear assertion message that describes the bug
- [ ] The failure description matches the root event from Step 2

**Do NOT proceed to fix until the testbench fails.** A passing testbench
does not reproduce the bug and will not protect against regression.

---

## Step 4 — Fix (modify RTL until testbench passes)

**Goal**: Change the minimal amount of RTL to make the testbench pass.

### Entry
Failing testbench from Step 3.

### Execution

1. Read the failing assertion to understand exactly what behavior is wrong.
2. Locate the responsible RTL line(s) — the test already targets the right
   module, so the search space is small.
3. Make the minimal fix. Prefer:
   - Reuse existing infrastructure (e.g. `Replacement.scala` PLRU rather than
     writing a new replacement policy)
   - Single-line or few-line changes over restructures
4. Run only the failing test until it passes:
   ```sh
   mill <module>.test.testOnly <package>.<TestClass>
   ```
5. Run the full module test suite to guard against regressions:
   ```sh
   mill <module>.test
   ```

### Exit gating
- [ ] The specific test that reproduced the bug now **passes**
- [ ] All other tests in the same module **still pass**
- [ ] No new warnings or compilation errors

---

## Step 5 — Verify (re-simulate with remu)

**Goal**: Confirm the fix improves real workload behavior, not just the
testbench.

### Entry
All tests passing from Step 4.

### Execution

Re-run the same invocation from Step 1. Compare:
- IPC before/after
- Cycle count before/after
- Hit/miss counts before/after (via wavepeek)
- Any difftest mismatches still present

```sh
just run-app microbench riscv32im --platform remu --app-args test \
  -- --batch --difftest remu --platform nzea \
  --sim-opt "target=tile watchdog=5" \
  --startup '{' step 10000 '}' and \
            '{' func trace wave-form on '}' and \
            '{' step 50000 '}' and \
            '{' stat print '}'
```

Optionally re-run Step 2 to confirm the root event no longer appears.

### Exit gating
- [ ] IPC or cycle count improved (or at least not degraded)
- [ ] The specific symptom from Step 1 no longer appears
- [ ] No new difftest mismatches or errors introduced

---

## Coordinating with other skills

| Skill | Used in step |
|-------|-------------|
| `remu` | Step 1 (simulation invocation), Step 5 (re-verification) |
| `wavepeek` | Step 2 (waveform analysis) |

Load each when entering its step; they carry the exact CLI syntax and
discovery patterns.

## Self-checks before declaring "done"

- [ ] Every step's exit gating items are checked off
- [ ] The testbench from Step 3 is committed alongside the fix
- [ ] The numeric delta (IPC, cycle count, hit ratio) is recorded in the
  commit message or PR description
