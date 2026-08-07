# Pulse Evolution Plan (design note, future plan)

Status: **design only** — recorded from the IFU/RAS/BPU-stats debugging
session (2026-08). Nothing here is implemented yet; pick items up one at a
time when an actual debugging task needs them (用到再说 principle).

## Background

pulse was exercised heavily while debugging the IFU prediction fix, the RAS
integration and the BPU stat counters. The items below are concrete gaps
felt from usage — each entry names the pain, sketches the fix, and gives a
priority. They are deliberately **usage-driven**, not feature-driven.

## 1. `--count` mode for property

Status: **implemented** (2026-08).

- **Pain**: debugging usually only needs "how many times did this event
  happen" (cache misses, mispredictions, rets...). Today the full range list
  must be printed and counted by hand.
- **Usage**: `property --eval '<expr>' --count` → print a single number.
- **Implementation**: the evaluator already scans cycle-by-cycle; counting
  is nearly free. Output would be one row (time-span + count) or just a
  number.
- **Priority**: high — pairs naturally with verifying the new `stat_*`
  counters (cross-check RTL counter vs. waveform-derived count).

## 2. Repeatable `--eval`: one scan, many columns

Status: **implemented** (2026-08). Text output is a union table via
`tabled`; single-`--eval` JSON stays unchanged for compatibility.

- **Pain**: a debug session usually tracks several events at once
  (e.g. `req_valid && req_ready`, `resp_valid`, `cache_miss`). Today that
  means running `property` N times, reopening the waveform and re-scanning
  each time — cost grows linearly with the number of expressions.
- **Usage**: `property --eval 'a' --eval 'b' --eval 'c'` → one pass,
  multi-column output (with `--count`, one number per expression).
- **Implementation**: the evaluator core is already "evaluate a set of
  signals every cycle"; extending it to a list of expressions is
  mechanical. Signals referenced by all expressions are loaded once.
  Text output uses a tabular layout (e.g. the `tabled` crate), one column
  per expression.
- **Priority**: high. Typical use: aligning several event streams
  cycle-by-cycle (e.g. `pred_taken` / `actual_taken` / their xor vs. the
  RTL `stat_bp_dir_mispred` counter).

## 3. Value comparison in expressions (`==`, `!=`, comparisons)

Status: **implemented** (2026-08). Operands are signals or integer literals
(decimal / `0x` hex); composite boolean expressions coerce to 0/1; X/Z
makes the comparison false for that cycle.

- **Pain**: expressions are boolean-only today (`&&`, `||`, `!`). "Address
  falls in RAM region" or "data equals 0xdeadbeef" cannot be expressed
  without pre-generating helper signals in the RTL.
- **Usage**: `io_req_addr >= 0x80000000 && io_req_addr < 0x88000000`,
  `io_data == 0xdeadbeef`.
- **Implementation**: add comparison nodes to the AST; evaluation happens at
  signal-value level. Needs a defined semantics for multi-bit values vs.
  1-bit Bool compatibility.
- **Priority**: medium — only when a concrete debugging task needs it.

## 4. History sampling instead of edge atomics

- **Pain**: counting flips or stall cycles requires hand-writing
  `!prev && cur` in every event file.
- **Direction (decided 2026-08)**: implement a single **history sampling
  primitive** `prev(sig, n)` (n=1 default, SVA `$past` semantics) rather
  than dedicated edge syntax. Defined standard-library semantics:
  - `rise(sig)`   ≡ `sig && !prev(sig)`
  - `fall(sig)`   ≡ `!sig && prev(sig)`
  - `stable(sig)` ≡ `sig == prev(sig)`
  These stay **docs-level equivalences, not syntax**: pulse follows the
  language-design philosophy that anything expressible with existing
  syntax should not get new syntax (the language already has temporal
  operators as syntax; signal sampling is a library-function concern).
  Sugar is only added later if hand-writing the composition becomes
  frequent enough to hurt.
- **Implementation**: evaluator keeps a per-signal history buffer (start
  with n=1; multi-cycle history only when a task actually needs it).
- **Priority**: low — an expression-level workaround exists.

## 5. Scope location by unique match (replaces "file declares scope")

Status: **implemented** (2026-08).

- **Pain**: a `.pulse` file's expressions use bare signal names; the scope
  binding lives only on the command line (`--event FILE SCOPE NAME`).
  Reusing a file against another scope instance is possible today
  (repeatable `--event`, e.g. one `cache.pulse` for both icache and
  dcache), but the SCOPE path must be typed fully and breaks when the
  simulation hierarchy changes (tile-level vs. core-level dump: `NzeaTile`
  does not exist in the latter).
- **Rejected (2026-08)**: declaring a default scope inside the file — any
  hardcoded path is fragile, exactly the problem this is meant to solve.
- **Direction**: SCOPE accepts a **unique-match pattern** (reuse the
  `scope --filter` traversal). `--event cache.pulse '*icache' Icache`
  resolves against the whole waveform: exactly one hit → use it; zero →
  "not found"; several → error listing candidates. Hierarchy-agnostic by
  construction (tile dump: `TOP.NzeaTile.icache`; core dump: `TOP.icache`).
- **Cost**: one extra hierarchy walk per event — can be merged with the
  signal-resolution pass that loading an event file already does.
- **Priority**: low.

## 6. property → value pipeline: round-trip consistency

- **Pain**: the typical flow is "find the moments an event fires, then
  inspect signal values at those moments". Today the ranges must be
  transcribed by hand between the two commands.
- **Usage**: `property --eval '<expr>' | pulse value --at - ...` — `--at`
  accepts stdin (`-` convention, same as `scope --root -`).
- **Nature (decided 2026-08)**: this is **not a new feature but a
  round-trip consistency requirement**: the text format property prints
  must be exactly what value's parser reads. Both already share the same
  type (`Range<T>`: `Display` + `Serialize` in `range.rs`, `FromStr` in
  `time_spec.rs`), so the work is turning that into a constraint:
  1. Every time-range type's `Display` and `FromStr` must be mutual
     inverses;
  2. a round-trip unit test per range type (`parse(display(x)) == x`);
  3. text output always formats ranges via `Display` — no second ad-hoc
     format;
  4. extend the `-` (stdin) convention to every entry point that accepts
     ranges/paths.
- **Status**: design to be finalized before implementation.
- **Priority**: medium.

## Already-landed lessons (write into the skill)

- **Scoping the search window is the biggest speedup**: `--cycles` /
  `--at` cut the scanned span linearly. The skill should insist on
  narrowing with `info`/`scope` first, then running `property` on the
  window.
- Specialized features added during a debugging task are fine; generalize
  them afterwards if they earn their place (the workflow the user
  established for pulse changes).

## Principles

1. Implement one item at a time, only when a real debugging task needs it —
   never batch-implement this list for its own sake.
2. Keep the human-readable output and the JSON output in sync with any new
   option (single `emit` path).
3. Prefer stdin-pipeline composition (`-`) over new inter-command
   persistence; it is how `scope`/`signal`/`value` already chain.
4. No new syntax for anything expressible with existing syntax; extend
   the standard function library instead.
4. No hardcoded paths anywhere in event files or defaults; locate scopes by
   unique match against the actual waveform hierarchy.
