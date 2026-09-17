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

## Mental model (read this first)

pulse is an **event engine + value reader** over waveform files. Four verbs:

| Verb | Command | Answers |
|---|---|---|
| Discover | `info` / `scope` / `signal` | What is in this dump? |
| Find time | `property` | WHEN did X happen? (event engine) |
| Read value | `value` | What value did X have then? |
| Show | `plot` / `diff` | Draw it / compare two runs |

Two time domains, one shared TimeBase (the posedge table):

- **tick** = raw waveform timestamp (`--at` bare numbers, `value` samples)
- **cycle** = Nth posedge of `clock` (`--cycles`, `--at Nc`, `property` matches)

Cross-cutting composition (no new verbs):

- `property --sample EXPR` — read values while finding events (commit-log style).
- `value --at 'match(EXPR)'` — use the event language as a time-point source.
- `--json` everywhere; `property` matches carry `{cycle, tick}` pairs so no
  manual conversion is ever needed.

The canonical workflow: `info` → `scope` → pick signals → `property` to find
interesting cycles → `--sample` (or `value --at match()`) to read the scene.

## Command routing by task

- Dump bounds, time unit, top-level scopes: `info`.
- Hierarchy discovery: `scope`.
- Signal discovery inside a known scope: `signal` (JSON carries each
  signal's bit `width` and `vector` flag, so rename-map keys and
  constant-folding checks can be derived from metadata alone).
- State at explicit timestamp(s) or ranges: `value`.
- Timestamps/ranges where a boolean or temporal event expression is true: `property`.
- **Event + value in one pass**: `property --sample EXPR` attaches a value
  column to every match (commit log in one command).
- **Events as time points**: `value --at 'match(EXPR)'` samples signals at
  every tick where the event expression fires — same evaluator as `--eval`.
- Render a waveform window as SVG (for humans/docs): `plot`.
- Compare two waveforms (pass/fail, before/after a fix): `diff`.
- Event files and cross-module composition: `property --event` + `--eval` (see below).
- Counting event occurrences (match counts, no range lists): `property --count`.
- Many queries in one process (one waveform load, shared signal cache):
  `batch` (see below).
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
| `a --N b` | `b` within N cycles **after** `a` (a is the origin; matches a) |
| `a N-- b` | `b` within N cycles **before** `a` (matches a) |
| `a N--M b` | `b` within `[N before, M after]` `a` — two-sided window (matches a) |
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

Standard-library functions: `rise`/`fall`/`stable` are templates written in
in the temporal language itself — the evaluator knows no built-in functions
for them. `change` and `is_x` are **built-ins**: `change` compares the
operand's **full value** against the previous cycle (multi-bit signals toggle
correctly, unlike the bit-level MSB semantics of `rise`/`fall`); `is_x`
reports cycles where the operand's value is unknown (X/Z), which comparisons
(`==`, `!=`) treat as false and are otherwise invisible in the event domain.
`is_x` makes X visible: `is_x(data) ->1 resp_valid`, `is_x(a) || change(b)`.
Composite subexpressions are booleans and never X, so `is_x(a && b)` is
constantly false.

| Function | Meaning |
|----------|---------|
| `rise(sig)` | `!sig ->1 sig` — rising edge |
| `fall(sig)` | `sig ->1 !sig` — falling edge |
| `stable(sig)` | `(sig ->1 sig) \|\| (!sig ->1 !sig)` — no change |
| `change(sig)` | value differs from the previous cycle (any edge) |
| `is_x(sig)` | value is unknown (X/Z) that cycle |

    pulse property --scope '*icache' \
        --eval 'rise(io_top_req_valid && io_top_req_ready)'

Stdlib functions may be used inside `.pulse` event files too (e.g.
`bp.pulse` defines `fetch_continue = fetch_fire ->1 1`). Adding a template
function is a one-line entry in
`pulse_wave/src/cmd/property/expr/std.pulse`; value-level built-ins (like
`change` and `is_x`) live in `stdlib.rs` as a named whitelist + evaluator
branch — only add those when the template language cannot express the
semantics (value history needs `read_value` across cycles, which templates
cannot reach).

Before the window start there is no history: the `->n` lookback is false,
so `rise`/`fall`/`stable` do not fire on the very first cycle, and `change`
has no previous value there. `rise(x)` fires on the cycle *after* `x` turns
true. A folded constant (never toggles) reports **0 change matches** — the
cheap way to spot impl-constant nets in ILA exports.

All temporal operators are composable: `miss ->3 (resp && !err)`, `(a -> b) && c`.

The window operator has one origin semantics: `a --N b`, `a N-- b` and
`a N--M b` are the same operator with `a` as the origin (the match is
reported on `a`'s cycle, and the window may reach into the future — the
waveform is fully loaded, so lookahead is free). `--N` and `N--` are
shorthand for `0--N` and `N--0`.

## Time windows and search speed

- `--cycles FROM-TO` selects a window of **cycles** (posedge-clock counts), not
  ticks: `0-100`, `500-`, `-100`, or omitted for the full trace. A window
  entirely past the trace end is an **error** (not silent empty matches) —
  e.g. `--cycles 1326400-1326542` on a 663271-cycle dump fails with
  "empty window".
- Prefer a strong `--cycles` window over `--max`: evaluation scans the whole
  window, so a narrow window is the primary speed lever. `--max` only truncates
  the output afterwards; it does not speed up the search.
- `value --at` takes ticks with optional units: `100`, `200-400`, `100-`
  (open-ended, clamped to the last tick), `-100` (from start), `~N` (last N
  ticks), or a comma-separated mix. `200-400` emits one row per tick in the
  range.
- **Unit suffixes on `--at`**: `100c` = the 100th cycle (posedge-clock count,
  resolved through the same TimeBase as `--cycles`; errors when past the
  trace), `100t` = tick 100 (same as bare `100`), `100ns` = absolute time in
  nanoseconds (converted via the dump's timescale, rounded down). Ranges and
  `-`/`~` forms stay in ticks.
- **`--at 'match(EXPR)'`**: sample at every tick where the event fires — the
  full expression language is available (`&&`, `->`, windows, `rise`/`fall`,
  `is_x`):

      pulse value --at 'match(flushing)' --signals redirect_bits,ex_pc --radix hex

  The scan covers the whole trace (no `--cycles` on `value`); expression
  signals are resolved in the same scope and need not be listed in
  `--signals`. Event-set references are not available here (no `--event`).
- `property --sample NAME` (repeatable) appends a per-match value column,
  single `--eval` only: signal names sample width-aware (radix via `--radix`,
  default `hex`); anything else is a value expression evaluated to 0/1
  (e.g. `--sample 'pc == 0x8000'`). X/Z samples render `?`. Mutually
  exclusive with `--count`; combines with `--cycles`/`--max`.
- `value --changes` (mutually exclusive with `--at`) scans the whole trace and
  emits **one row per tick where any requested signal's value differs from the
  previous tick** (the first tick always appears: initial state). Multi-signal
  rows stay aligned, so change sets can be diffed across signals — e.g. two nets
  with identical toggle history, or a folded constant (only the initial row).
  Combines with `--signals` and `--radix`.
- `value --at -N` means **from the start** (`0..=N`), NOT "last N ticks" — the
  same convention as `--cycles -N` (first N cycles). Use `~N` for the tail:
  `--at ~100` samples the last 100 ticks.
- `--cycles ~N` selects the **last N cycles** (e.g. `--cycles ~50` scans the
  final 50 clock edges).
- `--wave` is a global option: it may appear before the subcommand
  (`pulse --wave F info`) or after it (`pulse info --wave F`).
- `value --radix <bin|hex|dec|oct>` renders multi-bit signals in the chosen
  radix (default `bin`, the original behavior). E.g. `--radix hex` prints
  `io_top=80010eec` instead of a 32-bit 0/1 string. One-bit signals are
  unaffected (always 0/1). Works for both JSON and text output; X/Z values or
  vectors wider than 64 bits fall back to binary.

## Batch scripts (many queries, one process)

Each CLI invocation loads the waveform and scans the FST for the requested
signals (2s for a 15 MB dump); a script with many queries pays that per call.
`batch` runs all commands in **one process**: the waveform is loaded once and
loaded signals stay in the cache, so repeated queries are ~instant.

    pulse batch --wave trace.fst script.ppl      # script file
    pulse batch --wave trace.fst - <<'EOF'        # stdin
    property --scope TOP.NzeaTile.core --eval io_dbus_req_valid --count
    value --scope TOP.NzeaTile.core --at 603c --signals io_dbus_req_bits_addr --radix hex
    property --scope TOP.NzeaTile.core --eval io_dbus_req_valid --sample io_dbus_req_bits_addr --cycles 0-1000
    EOF

- One command per line; **syntax identical to the CLI** (quotes group words).
  Blank lines and `#` comments are skipped.
- First query on a signal still scans the whole FST; every later query on that
  signal is free. Group queries by signal to maximise the win.
- Errors stop the script (shell-script semantics) and the exit code is
  non-zero; `--keep-going` runs everything and still reports failure.
- Command echo (`> cmd`) goes to **stderr**; stdout carries only command
  output, so `--json` sessions are pure NDJSON (`| jq` works).
- `--wave` is session-level (not per line); inline `--json` is allowed and
  overrides the output format for that line only. `diff` works inside a
  script too (it takes `--wave-a`/`--wave-b` on its own line).

## Plotting (SVG output)

`plot` renders a waveform window as an SVG document — for humans, commit
messages, or docs. Same cycle semantics as `property` (posedges of `clock`, or
every time-table entry when the scope has no clock, e.g. ILA exports):

    pulse plot --scope <SCOPE> --signals a,b --cycles FROM-TO \
        --mark 'rise(a && b)' --out window.svg

- `--signals` is comma-separated; each signal gets one row (top to bottom).
- `--cycles` narrows the window (default: full trace); x axis = cycle index,
  with the tick underneath each axis label.
- `--mark EXPR` is repeatable: each expression's matches are drawn as dashed
  vertical lines (point events) or translucent bands (interval `~~` events),
  each in its own color with a top-left legend. Same expression language as
  `--eval`.
- Waveforms are step polylines; `X/Z` samples render as a translucent band
  (the line breaks across it); multi-bit vector values are labeled in hex at
  each change point.
- `--out FILE` writes the SVG to a file (default: stdout). Zero dependencies —
  the SVG is hand-generated, so it renders anywhere and has no image stack.

## Diffing (two waveforms)

`diff` compares two waveforms **aligned by cycle index** (posedges of `clock`,
or every time-table entry when the scope has no clock) — so time origins may
 differ (e.g. ILA captures with different trigger points):

    pulse diff --wave-a pass.fst --wave-b fail.fst \
        --scope <SCOPE> --signals a,b --eval 'commit_fire' --cycles 0-10000

- `--wave-a` / `--wave-b` name the two waves (this command does not use the
  global `--wave`). The scope defaults to each wave's top-level module.
- `--signals a,b` compares signal values at every cycle and reports the
  **first cycle where any value differs**, with the diverged signals and both
  values (`--json` carries the same detail).
- `--eval 'expr'` (repeatable) evaluates the expression on both waves and
  reports the first cycle where the two disagree — e.g. the first
  `commit_fire` placed differently. This is the pass/fail workflow: find the
  first divergent event without knowing any trigger condition in advance.
- Signal-level diff is literal per-cycle; event-level tolerates whole-wave
  shifts (the events are what matter). Use `--cycles` to bound the scan.

Typical uses in this repo: before/after an RTL change (does the behavior
move?), cosim vs ILA capture of the same program (which simulation assumption
is wrong?), and pass vs fail runs of the same program (where did the first
mismatch happen?).

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
When `--on` is **not** passed explicitly and the scope has **no clock
signal** (e.g. ILA VCD exports, whose timestamps are sample indices), every
time-table entry is treated as one cycle — `--cycles` and all matches are
sample indices, and `change`/`rise`/`fall` compare adjacent samples. Passing
`--on` explicitly still errors on a missing signal.

Temporal operators such as `->` and `~~` do their own pairing; they are not
`change`-style edge scans.

## JSON and diagnostics

Every command supports `--json`:

- `info`: `time_scale`, `time_start`, `time_end`, `top_scopes`
- `scope`: tree nodes `{name, depth, expanded?}` or flat `{path}` list
- `signal`: `{scope, signals: [{name, width, vector}]}`
- `value`: `{scope, signals, samples: [{time, <sig>: value, ...}]}`
- `property` (single `--eval`): `{from, to, total_cycles, max?, matches: [{cycle, tick} | {from: {cycle,tick}, to: {cycle,tick}}]}`
  — every match carries **both time domains**, so consumers never convert.
- `property --sample`: additionally `samples: [{cycle, tick, <name>: value, ...}]`
  (values in the `--radix` format, `"?"` for X/Z).
- `property --count` (single `--eval`): `{from, to, total_cycles, count}`
- `property` (multiple `--eval`): `{from, to, total_cycles, max?, columns: [{name, matches}]}`
- `property --count` (multiple `--eval`): `{from, to, total_cycles, columns: [{name, count}]}`

All time-based commands share one TimeBase: `--cycles` numbers, `--at Nc` and
`property` matches are the same cycle domain (Nth posedge of `clock`); ticks
are the raw waveform timestamps (`value` samples and `--at` bare numbers).
Text output renders the cycle; `--json` adds the tick on every match.
When the scope has no clock signal, a cycle *is* a time-table entry — the two
domains collapse, which is the ILA-export convention.

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
