---
name: pulse
description: Use this skill when you need to inspect or analyze `.vcd`, `.fst`, or `.fsdb` waveforms with the pulse CLI. Load it for dump metadata, hierarchy/signal discovery, point samples, boolean and temporal event queries, named event files with cross-module composition, and JSON-backed automation.
---

Use `pulse` for waveform questions. Treat waveform files as CLI inputs, not as text files to inspect directly.

This skill is a compact router, not the full command reference. It should choose the right analysis primitive, then route to installed help for exact syntax and edge-case semantics. Do not infer unsupported syntax from memory or from a nearby example.

## Safety and operating posture

- Do not read `.fst` or `.fsdb` files with generic text/binary tools. Avoid raw `.vcd` reads too; large dumps will waste context and can hide timing semantics.
- Confirm or infer these before expensive queries: waveform path, user goal, relevant scope/signals if known, clock if relevant, and target cycle window if any.
- Keep output bounded by default: use `--cycles` to narrow the window and `--max` to cap match count. Use unbounded output only when the expected result size is small or the user explicitly asks for it.
- Prefer `--json` for scripts, aggregation, and agent-side post-processing. Parse the envelope and check for error fields before trusting counts.
- The waveform path comes from `--wave <FILE>` or the `NZEA_TRACE_FST` environment variable. All commands share these global options: `--wave` and `--json`.

## Progressive disclosure

Use the installed binary as the source of truth for exact syntax, defaults, and build-specific features:

    pulse --help
    pulse help <command>
    pulse skill        # print this skill document (self-bootstrapping)

Before using any command in a nontrivial way, read `pulse help <command>`. Do this instead of trying to guess spellings.

## Command routing by task

- Dump bounds, time unit, top-level scopes: `info`.
- Hierarchy discovery: `scope`.
- Signal discovery inside a known scope: `signal`.
- State at explicit timestamp(s) or ranges: `value`.
- Timestamps/ranges where a boolean or temporal event expression is true: `property`.
- Event files and cross-module composition: `property --event` + `--eval` (see below).
- Counting event occurrences (match counts, no range lists): `property --count`.
- Machine parsing or aggregation: `--json` on every command.

Start most investigations with:

    pulse info --json

When names are unknown, use built-in discovery before shell filtering large outputs:

    pulse scope  --filter '<substring>' --flat --json
    pulse signal --scope <SCOPE> --json

`scope --filter` is a plain substring match, not a regex. `--flat` prints full
`TOP.a.b` paths (suitable for piping); without it, output is a tree with `+`
markers for unexpanded children. Use `--root <PATH>` to start from a specific
scope and `--depth <N>` to limit expansion.

## Naming discipline

Choose one naming mode per command:

- With `--scope <SCOPE>`, use signal names relative to that scope in `--signals`, `--eval`, `--on`, and `--event` files.
- Do not mix `--scope TOP.NzeaTile.icache` with references like `TOP.NzeaTile.icache.clock` in the same query.
- If name lookup fails, run `scope`, then `signal --scope <SCOPE>`, then rewrite the query in one naming mode.

SCOPE accepts a `*` glob pattern that must match **exactly one** scope in the
whole hierarchy — hierarchy-agnostic, so the same query works for tile-level
and core-level dumps:

    pulse signal --scope '*icache'          # TOP.NzeaTile.icache or TOP.icache
    pulse value  --scope '*.core.ifu.ras'   # unique match required

Zero matches and multiple matches are both errors (multiple lists the
candidates). Prefer `*` patterns over hardcoding `TOP.NzeaTile...` paths.

## Event definitions and cross-module composition

Named events make queries reusable and let one `--eval` span multiple scopes.

An event set binds a source to a scope and gives it a namespace name:

    pulse property \
        --event ../nzea_cache/cache.pulse TOP.NzeaTile.icache Cache \
        --event ../nzea_core/core.pulse   TOP.NzeaTile.core   Core \
        --eval "Core.commit_fire && Cache.miss" \
        --cycles 0-3000 --max 10 --json

- `--event SOURCE SCOPE NAME` consumes **three separate arguments** (repeatable).
  SOURCE is either a `.pulse` file or an inline `name = expr` string; quote the
  inline source when it contains spaces: `--event "bus_transfer = req_valid && req_ready -> resp_valid" TOP.NzeaTile.icache Icache`.
  SCOPE accepts the same `*` unique-match glob as `--scope`.
- Inside the eval expression, `Namespace.name` references a defined event;
  bare names resolve to the `--scope` (default: top-level module).
- Inline sources also work, but must be written **without spaces** (the tuple
  is whitespace-split): `--event "bus_transfer=req_valid&&req_ready->resp_valid TOP.NzeaTile.icache Icache"`.
- Undefined signals are an error, never a silent empty result. Undefined event
  namespaces in `--eval` are also an error.

## Expression language

Boolean and temporal operators, with `&&`/`||`/`!` at lowest precedence and
temporal operators binding tighter:

| Syntax | Meaning |
|--------|---------|
| `a && b` / `a \|\| b` / `!a` | Boolean composition (any nesting, parentheses) |
| `a -> b` | first `b` after each `a` (non-overlapping FIFO pairing) |
| `a ->N b` | `b` exactly N cycles after `a` |
| `a --N b` | `b` within N cycles after `a` |
| `a ~> b` | every `b` after `a` (overlapping) |
| `a ~~ b` | interval from `a` to `b` (emits a range) |
| `a |-> b` | `a` implies `b` in the same cycle |
| `a >>N b` | pipeline sequence: `a`, then `b` after N cycles |
| `sig[N]` | `sig` true for N consecutive cycles |

Value comparisons bind tighter than `&&`/`||`; operands are signals or
integer literals (decimal or `0x` hex):

| Syntax | Meaning |
|--------|---------|
| `a == b` / `a != b` | integer equality / inequality |
| `a < b` / `a <= b` / `a > b` / `a >= b` | integer comparison |

    pulse property --scope '*icache' \
        --eval 'io_top_req_valid && io_top_req_bits_addr >= 0x80000000 && io_top_req_bits_addr < 0x88000000'

Comparisons read multi-bit signals as unsigned integers; X/Z values make the
comparison false for that cycle. Composite boolean expressions are usable as
operands too (0/1).

Standard-library functions (call syntax; every function is a template
written in the temporal language itself — the evaluator has no built-in
functions):

| Function | Meaning |
|----------|---------|
| `prev(sig, n)` | `sig` was true n cycles ago (`sig ->n 1`; n ≥ 1 constant) |
| `rise(sig)` | `!sig ->1 sig` — rising edge |
| `fall(sig)` | `sig ->1 !sig` — falling edge |
| `stable(sig)` | `(sig ->1 sig) \|\| (!sig ->1 !sig)` — no change |

    pulse property --scope '*icache' \
        --eval 'rise(io_top_req_valid && io_top_req_ready)'

Stdlib functions may be used inside `.pulse` event files too (e.g.
`bp.pulse` defines `fetch_continue = prev(fetch_fire, 1)`). Adding a new
library function is a one-line template in
`pulse_wave/src/cmd/property/expr/std.pulse` — but only add functions that
express something the core syntax does not already say concisely (`&&`
aliases are noise).

Before the window start there is no history: the `->n` lookback is false,
so `rise`/`fall`/`stable` do not fire on the very first cycle. `rise(x)`
fires on the cycle *after* `x` turns true.

All temporal operators are composable: `miss ->3 (resp && !err)`, `(a -> b) && c`.

## Time windows and search speed

- `--cycles FROM-TO` selects a window of **cycles** (posedge-clock counts), not
  ticks: `0-100`, `500-`, `-100`, or omitted for the full trace.
- Prefer a strong `--cycles` window over `--max`: evaluation scans the whole
  window, so a narrow window is the primary speed lever. `--max` only truncates
  the output afterwards; it does not speed up the search.
- `value --at` takes ticks with optional units: `100`, `200-400`, `100-`
  (open-ended, clamped to the last tick), `-100` (from start), or a
  comma-separated mix. `200-400` emits one row per tick in the range.

## RTL event model

For synchronous RTL, the default mental model is one evaluation per clock
cycle:

    pulse property --scope <SCOPE> --on "posedge clock" --eval "<cond>" --cycles FROM-TO

`--eval` is repeatable: each expression becomes one column of a union table
(rows are time segments partitioned by all match endpoints, cells are 0/1),
so several events can be aligned cycle-by-cycle in a single scan:

    pulse property --scope '*icache' \
        --eval 'io_top_req_valid && io_top_req_ready' \
        --eval 'io_top_resp_valid' \
        --cycles 0-3000

`--count` prints match counts instead of ranges (single `--eval`: one number;
multiple: an expr/count table). It is mutually exclusive with `--max` and
counts **all** matches in the window, so it is the cheap way to quantify an
event (e.g. cross-checking `stat_*` counters).

`--on` defaults to `posedge clock`; only `posedge` is supported for now.
Temporal operators such as `->` and `~~` do their own pairing; they are not
`change`-style edge scans.

## JSON and diagnostics

Every command supports `--json`:

- `info`: `time_scale`, `time_start`, `time_end`, `top_scopes`
- `scope`: tree nodes `{name, depth, expanded?}` or flat `{path}` list
- `value`: `{scope, signals, samples: [{time, <sig>: value, ...}]}`
- `property` (single `--eval`): `{from, to, total_cycles, max?, matches: [<t> | {from,to}]}`
- `property --count` (single `--eval`): `{from, to, total_cycles, count}`
- `property` (multiple `--eval`): `{from, to, total_cycles, max?, columns: [{name, matches}]}`
- `property --count` (multiple `--eval`): `{from, to, total_cycles, columns: [{name, count}]}`

`property` matches are scalar ticks for point events and `{from,to}` objects
for interval events (`~~`).

## Recovery patterns

- If a command fails with a name error, switch to discovery: `scope`, then
  `signal --scope`, then rerun in one naming mode.
- If a query returns empty, check the window (`--cycles` vs actual trace
  bounds from `info`), check the scope (tree vs flat naming), and verify the
  signal names with `signal`.
- If an `--event` source is not found, it is treated as an inline definition
  and fails with `expected 'name = expr'` — pass the correct relative path.

## Nzea project specifics

- Waveforms are generated under `build/sim/` by `just iv tile` / `just iv fpga`
  (e.g. `build/sim/tile/<platform>/<isa>/hw/iverilog/tb.fst`), or by remu at
  `../remu/target/trace.fst` (usually already set as `NZEA_TRACE_FST`).
- Event files live next to their modules: `nzea_cache/cache.pulse`,
  `nzea_core/core.pulse`. Signals in those files are relative to the scope you
  bind with `--event`.
- Agent must run pulse via `nix develop --command bash -c '...'` (same as all
  Nix-dependent tools).
