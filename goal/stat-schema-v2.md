# nzea stat schema v2: source attribution (handoff, draft)

Status: **landed on both sides (2026-09-29).** nzea emits `schema = 2` (per-block declarations carrying their own
`source`, scoped counter names) and remu accepts it; §4 of `stat-schema.md` is the normative format. This file stays as
the record of what was asked. (Same-day follow-up: declarations moved to the call site — a counter is now named after
the declaring scope's whole path (`stat_core_middle_cycle`), one box module per counter, named after the counter; see
`stat-schema.md` §11.)

Audience: remu (the stat schema reader and the `stat` display). Which counters exist, and how they are declared,
stays nzea's business; §1–§4 are the whole contract.

---

## 1. What changes

Every `[[counter]]` and every `[[derived]]` gains one required key: **`source`** — the design-time owner of the
entry: the block that declares it. It is a short lowercase identifier (`middle`, `int`, `ifu`, `mem`, ...), not an
instance path. Which *instance* a counter belongs to stays in its name (`stat_core0_middle_cycle`), which is what
the VPI readout and the "counter the RTL does not expose" check already work on.

Motivation: `region` says what a number *measures* (`throughput`, `bp`); it does not say *who produces it*. With
only that one axis, a tool can group entries in one order and cannot attribute a missing or broken counter to a
block. With both fields, both group orders are available (`by source`, `by region`), and a counter the RTL does
not expose names the block to go and look at.

## 2. Schema delta

```toml
schema = 2
design = "NzeaCore"

[[region]]
name = "throughput"
doc  = "Retire throughput"

[[counter]]
name   = "stat_core_middle_cycle"
width  = 64
source = "middle"
region = "throughput"
doc    = "core clock cycles"

[[derived]]
name   = "ipc"
expr   = "stat_core_middle_commit / stat_core_middle_cycle"
source = "middle"
region = "throughput"
unit   = ""
digits = 4
doc    = "instructions per cycle"
```

- v1 files (`schema = 1`, no `source`) stop being emitted; nothing else changes — the same keys, the same
  in-order evaluation of `derived`, the same `stat_` prefix rule, the same "unavailable → `n/a`" semantics.
- `source` is required on **counters and derived entries**; regions are cross-block subjects and carry none.

## 3. Validation nzea guarantees (and suggests remu enforces)

- `schema = 2` accepted; `1` may be dropped (§5).
- `source` is non-empty and matches `^[a-z][a-z0-9_]*$`. Unknown values are fine: the set grows with the design,
  there is no whitelist.
- Everything v1 already checks: duplicate names, unknown region, `stat_` prefix, width range, derived expression
  syntax and in-order references, and `design` matching the file name.

## 4. What remu could do with it (suggestions, not requirements)

- `stat print` gains a grouping axis: today's `--group region`, a new `--group source`, or both as two levels;
  derived entries sort under the same keys as their counters.
- A missing counter or a schema that fails to load can name the `source` of the entry it concerns, which is where
  the RTL is to be inspected.
- `stat ipc`-style lookups by name are unchanged.

## 5. Landing order

1. **remu**: accept `schema = 2` — a version check plus two required keys. The reader is already schema-driven, so
   this is small.
2. **nzea**: emit v2 together with the declaration rework (per-block declarations carrying their own `source`).
3. Until step 2 lands, remu runs against v1 dumps exactly as today.

The difftest path does not touch the schema (it rides the commit trace), so only the `stat` commands depend on the
version: an nzea dump that emits v2 before remu accepts it breaks `stat`, not `--difftest`.

## 6. Out of scope here

- Which counters exist, and the config-driven polymorphism of that set (nzea-internal; the schema carries whatever
  the RTL exposes).
- Region hierarchy, windowed sampling, and attribution of multi-instance designs beyond the counter name
  (candidates for a later version).
