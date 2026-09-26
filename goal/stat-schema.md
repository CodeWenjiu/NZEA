# Stat schema: requirements for the remu side (handoff)

Status: **both sides implemented** — nzea emits and validates the schema with every simulation dump; remu reads it, evaluates
it and renders it (`remu_simulator_nzea::stat_schema`). The acceptance values in §8 and the negative paths
(R8–R11) were reproduced from this tree.

Scope: the statistics path only (`stat` command / `stat_*` VPI counters / derived values). Nothing else in remu.

Migration note (2026-09-26): the `nzea_*` implementation paths referenced below are frozen in `legacy/`
(tag `legacy-baseline`) while the core is rewritten on Scala 3 / zaozi. The specification itself stays in
force and is re-implemented on the new tree.

Verification owner: the nzea side provides the artifact, the reference values (§8) and the acceptance run; the
requirements below are testable one by one.

---

## 1. Problem

Today remu owns the semantics of nzea's counters:

- `simulators/nzea/src/stat_derive.rs` is a hardcoded table of rules with Rust closures;
- `remu_simulator/src/stat.rs` hardcodes the `stat ipc` / `stat bp` subcommands (`StatCmd::{Ipc, Bp}`);
- value formatting (`{ipc:.4}`, `%`) and display order are decided in Rust;
- raw counters are truncated to 32 bits: `nzea_wrapper.cpp` passes `uint32_t` read with `vpiIntVal`, so the 64-bit
  `stat_cycle` wraps on long runs;
- rules outlive the counters they read: `stat_bp_dir_mispred`, `stat_bp_pred_taken`, `stat_bp_ret`,
  `stat_bp_ret_mispred` are referenced today but do not exist in the current BRU.

Consequence: every metric, region or microarchitecture change requires a remu change, and remu silently carries
knowledge that only the RTL design has.

**Target ownership:** the design declares what it measures, how derived values are computed and how they are
classified; remu reads the declaration, evaluates it and renders it. remu keeps only generic machinery: counter
readout, expression evaluation, formatting, tables.

## 2. Glossary

| Term | Meaning |
|------|---------|
| counter | a `stat_*` register in the RTL, addressed by its VPI leaf name |
| region | design-defined classification of counters/derived entries (`throughput`, `bp`, ...) |
| derived | a value computed from counters and earlier derived entries by an expression |
| unavailable | a derived entry that cannot be computed (missing reference, division by zero) — reported as `n/a`, never as a fabricated number |

## 3. Deliverable and interface

- **Artifact:** `<dir>/<Design>.stats.toml` (`NzeaCore.stats.toml` / `NzeaTile.stats.toml`), emitted by nzea's dump
  into `<dir>` — the directory that also holds `filelist.f`, i.e. the verilog directory remu already knows
  (`verilog_dir()` in `runtime.rs`). Observed today for `core`/`riscv32i`:
  `target/remu-so/nzea/core/riscv32i/nzea-verilog/core/riscv32i/NzeaCore.stats.toml`.
- **Only simulation dumps contain it** (counters exist only when `sim = true`; FPGA builds have none).
- **Build artifact:** written on every dump. Hand-edited copies in the model cache are overwritten.
- **No cache-key change needed:** the model-cache hash already covers nzea's `.scala` sources and justfile, so any
  schema change invalidates the model like an RTL change does.
- **Version policy:** `schema` is an integer; a value the parser does not know must fail loudly (no guessing).

## 4. File format (normative, schema = 1)

```toml
schema = 1
design = "NzeaCore"

[[region]]
name = "throughput"
doc  = "Retire throughput"

[[counter]]
name   = "stat_cycle"
width  = 64
region = "throughput"
doc    = "core clock cycles"

[[derived]]
name   = "ipc"
expr   = "stat_inst_commit / stat_cycle"
region = "throughput"
unit   = ""
digits = 4
doc    = "instructions per cycle"
```

| Key | Type | Meaning |
|-----|------|---------|
| `schema` | integer | format version (currently `1`) |
| `design` | string | top module name of the described RTL (sanity check only) |
| `region.name` / `region.doc` | string | region identifier / one-line help |
| `counter.name` | string | VPI leaf name; globally unique; `stat_` prefix |
| `counter.width` | integer | register bit width; **read exactly this many bits**; range `1..64` |
| `counter.region` | string | must match a declared region |
| `derived.name` | string | display name; must not collide with a counter |
| `derived.expr` | string | expression, see §5 |
| `derived.region` | string | must match a declared region |
| `derived.unit` | string | appended verbatim to the formatted value (`""`, `"%"`, ...) |
| `derived.digits` | integer | decimal places for the derived value; range `0..30` |
| `counter.doc` / `derived.doc` | string | one-line help |

Structural rules: every counter/derived belongs to exactly one declared region; declaration order **is** display
order; counters precede derived entries in the emitted file; the numeric ranges in the table above are part of schema
v1 and are enforced by the generator (nzea's `validate()`), so a file either satisfies them or is never written.

## 5. Expression grammar and evaluation semantics (normative)

```
expr   := term (('+' | '-') term)*
term   := factor (('*' | '/') factor)*
factor := '-' factor | number | ident | ident '(' expr (',' expr)* ')'
```

- `number`: decimal, optionally fractional (`1`, `100`, `0.5`); no exponent, no suffix.
- `ident`: a counter name, or a derived name declared **above** this entry.
- functions: `abs(x)`, `floor(x)`, `max(a, b)`, `min(a, b)`. Nothing else. No conditionals, loops, string ops or host
  hooks; an expression that does not parse this grammar cannot occur in a valid file.
- Values are `f64`; counters enter as their unsigned integer value.
- Entries are evaluated **in file order**, so dependency cycles cannot exist and no graph resolution is required.
- Unavailability propagates: if a reference is unavailable (counter not in the VPI set, or an unavailable derived
  entry) or a division has a zero denominator, the entry is unavailable.
- Rendering: derived values print with `digits` decimals followed by `unit` verbatim; percentage scaling is explicit
  in the expression (`* 100`, `unit = "%"`); counters print as plain decimal integers. No other formatting rules exist.

Reference implementation of the grammar: `nzea_rtl/src/StatExpr.scala` (parser + evaluator, ~120 lines, port 1:1).

Evaluation shape (pseudo-code, the only behaviour that matters):

```
values = { c.name -> Some(vpi_value(c.name)) for c in counters }   # absent register -> None
for d in derived:                                                  # file order, so no cycles
    values[d.name] = eval(parse(d.expr), values)                    # None propagates, x / 0 -> None
```

## 6. Requirements

| # | Requirement | How it is verified |
|---|-------------|--------------------|
| R1 | The schema file is loaded from the same directory as `filelist.f`; loading happens once per model build/load, before the first `stat` command can run. | `stat` after a fresh model build prints schema-defined entries. |
| R2 | `stat_derive.rs` is deleted; no derived entry, group or format string is hardcoded in remu any more. | `grep -rn "DeriveRule\|NZEA_DERIVE_RULES" remu_simulator` returns nothing. |
| R3 | Counters are read with their declared width; the C callback carries `u64` values (`nzea_iter_stats` / `nzea_stat_callback` signature change in `nzea_wrapper.cpp`, `collect_stat_cb` in `simulator_trait.rs`). Keep the Verilator double-read quirk. | `stat_cycle` on a run longer than 2^32 cycles (or a unit test on a synthetic value > `u32::MAX`) is not truncated. |
| R4 | `stat` (no argument) prints every counter, then every derived entry, in **file order**; the order is deterministic across runs. | Two consecutive `stat` invocations print identical ordering. |
| R5 | `stat raw` prints counters only. | — |
| R6 | `stat <query>` resolves an exact counter or derived name first, then a region name; unknown queries fail with an error listing the available names and regions. | `stat ipc`, `stat throughput`, `stat bp`, `stat nosuch` (error). |
| R7 | A region query prints that region's counters (file order) followed by that region's derived entries (file order). | `stat bp` shows 3 counters + `mispredict_rate` + `taken_rate`. |
| R8 | Derived values are formatted from `digits` + `unit`; unavailable entries print `n/a` plus a reason (missing counter name / division by zero), never `0`, `inf` or `NaN`. | Force `stat_cycle = 0` (or a missing counter in a scratch schema) and check `n/a`. |
| R9 | A counter declared in the schema but absent from the VPI set is a hard error naming the counter (build mismatch). | Scratch schema with a bogus counter → error. |
| R10 | A `stat_*` register found in VPI but not declared in the schema produces a warning naming it (exposed but undeclared). | Scratch RTL/schema pair. |
| R11 | When the schema file is missing and the user asks for statistics, remu fails with a clear message ("this build exposes no stats"). There is **no** fallback table. | FPGA build (no counters) → clear error, no fake numbers. |
| R12 | Existing invocations keep working: `stat print` (alias of `stat`), `stat raw`, `stat ipc`, `stat bp`. | The command table in `remu_cli/src/skill.md` (printed by `--skill`) stays valid; the alias is documented there. |
| R13 | `stat --list`-style output is optional, but region `doc`/entry `doc` strings are available for display. | — (nice-to-have) |

## 7. Edge-case behaviour matrix

| Situation | Expected behaviour |
|---|---|
| schema file missing, stats requested | error: "this build exposes no stats"; no entries printed |
| `schema` value unknown | error naming the supported version |
| TOML malformed / missing required key | error with the file path and the offending key |
| expression does not parse | cannot occur in a valid file (nzea validates before writing); if it does, error at load, do not silently skip |
| `stat_cycle = 0` and a rule divides by it | that derived entry prints `n/a (div by zero: stat_cycle)`; other entries unaffected |
| counter declared but not present in VPI | hard error naming the counter (R9) |
| `stat_*` in VPI but not declared | warning naming it; the counter is **not** displayed as a stat (the schema is the contract) |
| region with counters but no derived entries | `stat <region>` prints just the counters |
| two regions, `stat` (all) | counters then derived, file order; grouping/chrome is a presentation choice |
| repeated `stat` while paused | identical values and identical ordering |

## 8. Definition of done and acceptance values

DoD: R1–R12 implemented; `cargo fmt` + `cargo clippy` clean; the interactive command table in `remu_cli/src/skill.md`
(embedded into the binary via `include_str!`, printed by `--skill`) updated to `stat [print|raw|<name|region>]`; the
acceptance run below reproduces the reference numbers. nzea mirrors that table in `.agents/skills/remu/SKILL.md`
(synced).

Verified (re-checked after the follow-up round): R1–R12 reproduced from this tree, including the negative paths and the
follow-up items below.

| Item | Status | Evidence |
|------|--------|----------|
| declared vs actual counter `width` cross-check | done | callback carries `(name, lo, hi, width)`; declaring `stat_cycle` as 32-bit against a 64-bit RTL fails with both widths named |
| non-finite values render `n/a (overflow)` | done | an overflowing rule prints `n/a (overflow)` |
| undeclared-counter warning order stable | done | two undeclared counters warned in name order, both ignored |
| wide-readout unit test | done | `assemble_stat_value` covered (narrow / >32 bit / zero-extension); 17 crate tests pass |
| commit the work (`skill.md`, `cargo fmt` together) | **open** | still uncommitted in the working tree (`stat_schema.rs` untracked) |

`cargo fmt --all --check` clean; `stat_schema.rs` clippy-clean (the crate's remaining lints are the pre-existing
transmute block in `nzea_ffi.rs`).

Reference run (nzea tree as of this document; values captured before the remu change with remu's hardcoded rules, then
re-confirmed after it):

| command | expected |
|---|---|
| `just run-app hello_world riscv32i -- --platform nzea --difftest remu --batch --startup "{ continue } and { stat print }" --sim-opt watchdog=3x5` | `stat_cycle` 1703, `stat_inst_commit` 392, `stat_bp_branch` 84, `stat_bp_mispred` 66, `stat_bp_actual_taken` 67, `ipc` 0.2302, `cpi` 4.3444, `mispredict_rate` 78.57%, `taken_rate` 79.76% |
| `just run-app microbench riscv32im -- --platform nzea --difftest remu --batch --startup "{ continue } and { stat ipc }" --app-args test --sim-opt watchdog=3x5` | `ipc` 0.2173 (`stat_cycle`/`stat_inst_commit` around 3111800/676232) |

`hello_world` reproduces bit-exactly. On longer runs the totals drift slightly — they depend on how far the DUT is
stepped past the finisher before the run stops, which varies run to run (observed on microbench, same build:
3111440/676143, 3111599/676185, 3111800/676232). `ipc` is stable at 4 digits. Compare `ipc` and treat counter totals as
approximate there. The rules whose counters no longer exist (`dir_mispred_rate`, `tgt_mispred_rate`, `pred_taken_rate`,
`ret_*`) must simply not appear — no `0.00%` placeholders.

## 9. Do not do

- Do not keep a built-in fallback table for platforms without a schema.
- Do not special-case any counter or derived name (`ipc` must work because it is in the file, not because it is
  known to Rust code).
- Do not format values in Rust beyond `digits` + `unit` from the schema, and do not re-scale (percentages are
  expressed in the expression).
- Do not render unavailable entries as numbers.
- Do not reorder entries by name/size/existence; file order is the display order.
- Do not rename counters: `counter.name` is the VPI interface to the RTL.

## 10. Current remu map (where the changes land)

| File | Change |
|------|--------|
| `remu_simulator/simulators/nzea/src/stat_derive.rs` | delete |
| `remu_simulator/simulators/nzea/src/stat_schema.rs` (new) | TOML parse (workspace `toml`, `str.parse::<toml::Table>()` needs no serde derive), expression parser/evaluator, ordered evaluation |
| `remu_simulator/src/stat.rs` | `StatCmd::{Print, Raw, Ipc, Bp}` → `stat [print|raw|<query>]`; `StatFilter::{All, Raw, Group}` may stay (`Group` carries a region name from the file) |
| `remu_simulator/simulators/nzea/src/simulator_trait.rs` | `platform_stats()`: iterate the schema instead of `NZEA_DERIVE_RULES`; schema-owned ordering; `collect_stat_cb` value type `u64` |
| `remu_simulator/simulators/nzea/src/nzea_ffi.rs`, `c_src/nzea_wrapper.cpp` | `nzea_stat_callback(const char*, uint64_t, void*)`; read full-width values (`vpiVectorVal`/`vpiLongIntVal`), keep the double-read quirk |
| `remu_simulator/simulators/nzea/src/runtime.rs` | load the schema from `verilog_dir()` after the dump (same place as `filelist.f`) |
| `remu_harness/src/lib.rs` | `stat_exec()`: build the filter from the query string, pass schema-backed entries to the tracer |
| `remu_debugger/src/flow/command.rs` | `Command::Stat` accepts an optional query argument |
| `remu_cli/src/skill.md` | command table: `stat [print|raw|<name|region>]`, note that regions/names come from the schema |

## 11. nzea side (already in tree, for reference)

| Path | Role |
|------|------|
| `nzea_rtl/src/StatSchema.scala` | types, `validate()`, reference `evaluate()`, `toToml()`, `emit()` |
| `nzea_rtl/src/StatExpr.scala` | grammar + evaluator (the normative reference for §5) |
| `nzea_rtl/src/StatsRegs.scala` | `public_flat_rd` counter black box; declarations drive RTL **and** schema |
| `nzea_core/src/stats/CoreStats.scala` | declarations: regions `throughput`, `bp`; counters `stat_cycle`, `stat_inst_commit`, `stat_bp_branch`, `stat_bp_mispred`, `stat_bp_actual_taken`; derived `ipc`, `cpi`, `mispredict_rate`, `taken_rate` |
| `nzea_core/src/CoreElaborate.scala`, `nzea_tile/src/TileElaborate.scala` | `StatSchema.emit(...)` on `sim` dumps |
| `nzea_core/test/src/stats/CoreStatsTest.scala`, `nzea_rtl/test/src/StatSchemaTest.scala` | pin the emitted counters/widths and the grammar semantics |

Guarantees: the schema is validated before it is written (unique names, known regions, expression syntax, resolvable
references), and a test asserts the emitted RTL exposes **exactly** the declared counters with the declared widths —
so a schema reaching remu is well-formed and matches its RTL.

## 12. Governance and out of scope

- Adding/changing a metric is an nzea change (declaration + RTL + tests). If remu needs a new derived value, it should
  be added to the declarations, not to remu.
- Counter names are an interface; treat renames as breaking for scripts and difftest baselines.
- Out of scope for v1: multi-region membership (one region per entry), windowed/sampled statistics (deltas between two
  samples), event-triggered captures, stall attribution beyond arithmetic over counters.
