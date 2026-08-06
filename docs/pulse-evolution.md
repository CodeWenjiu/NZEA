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

- **Pain**: a debug session usually tracks several events at once
  (e.g. `req_valid && req_ready`, `resp_valid`, `cache_miss`). Today that
  means running `property` N times, reopening the waveform and re-scanning
  each time — cost grows linearly with the number of expressions.
- **Usage**: `property --eval 'a' --eval 'b' --eval 'c'` → one pass,
  multi-column output (with `--count`, one number per expression).
- **Implementation**: the evaluator core is already "evaluate a set of
  signals every cycle"; extending it to a list of expressions is
  mechanical.
- **Priority**: high.

## 3. Value comparison in expressions (`==`, `!=`, comparisons)

- **Pain**: expressions are boolean-only today (`&&`, `||`, `!`). "Address
  falls in RAM region" or "data equals 0xdeadbeef" cannot be expressed
  without pre-generating helper signals in the RTL.
- **Usage**: `io_req_addr >= 0x80000000 && io_req_addr < 0x88000000`,
  `io_data == 0xdeadbeef`.
- **Implementation**: add comparison nodes to the AST; evaluation happens at
  signal-value level. Needs a defined semantics for multi-bit values vs.
  1-bit Bool compatibility.
- **Priority**: medium — only when a concrete debugging task needs it.

## 4. Edge atomics (`rise(sig)`, `fall(sig)`)

- **Pain**: counting flips or stall cycles requires hand-writing
  `!prev && cur` in every event file.
- **Usage**: `rise(io_req_valid)`, `fall(io_req_valid)` as atomic events.
- **Implementation**: a stateful primitive in the evaluator.
- **Priority**: low — an expression-level workaround exists.

## 5. Event files bound to scope

- **Pain**: a `.pulse` file's expressions use bare signal names; the scope
  binding lives only on the command line (`--event FILE SCOPE NAME`). A
  file like `core.pulse` cannot be reused against another scope without
  duplicating it.
- **Direction**: allow a default scope declaration inside the file
  (e.g. `# scope TOP.NzeaTile.core`), overridable from the command line; or
  let expressions carry namespace prefixes.
- **Priority**: low.

## 6. property → value pipeline

- **Pain**: the typical flow is "find the moments an event fires, then
  inspect signal values at those moments". Today the ranges must be
  transcribed by hand between the two commands.
- **Usage**: `property --eval '<expr>' | pulse value --at - ...` — `--at`
  already accepts stdin.
- **Implementation**: property's range output is already the
  `FROM-TO`/`FROM-TO:STEP` dialect that `--at` parses; mostly a matter of
  keeping those formats aligned.
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
