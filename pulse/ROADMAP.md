# pulse — Roadmap

pulse is a waveform event engine and query tool for RTL debugging.
It replaces wavepeek with a simpler, layered design: signal queries at the bottom,
named-event composition in the middle, and a minimal query CLI at the top.

Status: **implemented as of 2026-08**; the sections below record what exists
today.

## Architecture

```
┌─────────────────────────────────────────┐
│  Query layer   (pulse_cli)              │
│  $ pulse property --event cache.pulse  │
│      --scope '*icache' --eval 'miss'   │
│      --cycles 0-3000 --count --json    │
└──────────────────┬──────────────────────┘
                   │ parses/normalizes, binds scopes
┌──────────────────▼──────────────────────┐
│  Expression layer (pulse_wave)          │
│  ast / parser (winnow) / walk / eval    │
│  stdlib = std.pulse templates           │
│  .pulse files per RTL module            │
└──────────────────┬──────────────────────┘
                   │ reads signal values via wellen
┌──────────────────▼──────────────────────┐
│  Engine layer   (wellen)                │
│  Opens FST/VCD, per-signal value access │
└─────────────────────────────────────────┘
```

## Phases

### Phase 1 — Core commands (pulse_wave)

- [x] Open FST/VCD waveforms (wellen backend)
- [x] `info`: time scale, bounds, top-level scopes
- [x] `scope`: tree / flat listing, substring filter, `*` glob unique-match
- [x] `signal`: signal names within a scope
- [x] `value`: point/range sampling (`--at 100,200-400,-100,100-`)
- [x] JSON output contract (`--json` on every command)

### Phase 2 — Property engine

- [x] `.pulse` event-definition files (`name = expr` per line, `--` comments)
- [x] Scope applied at query time (`--scope` / `--event SCOPE`), not in the file
- [x] Boolean event composition (`&&`, `||`, `!`)
- [x] Value comparison (`==`, `!=`, `<`, `<=`, `>`, `>=`; dec/`0x` literals)
- [x] Cross-module event reference via namespace prefix (`Cache.miss`)
- [x] Fail-fast on undefined signals/namespaces (no silent empty results)
- [x] Repeatable `--eval`: one scan, multi-column union table
- [x] `--count` (match counts), `--max` (post-eval truncation), `--cycles`
  (window constraint — the primary speed lever)

### Phase 3 — Temporal operators

- [x] `A -> B` : first B after A (FIFO pairing)
- [x] `A ->N B` : B exactly N cycles after A
- [x] `A --N B` / `A N-- B` / `A N--M B` : B within [N before, M after] A
  (single origin semantics; degenerate forms are shorthand)
- [x] `A ~> B` : every B after A (overlapping)
- [x] `A ~~ B` : interval from A to B
- [x] `A |-> B` : A implies B same cycle
- [x] `A >>N B` : pipeline sequence
- [x] `sig[N]` : true for N consecutive cycles
- [x] Operators are composable with `&&`/`||`/`!` and each other
- [x] Stdlib function calls (`name(args)`) expanded from `std.pulse`
  templates: `rise`, `fall`, `stable` — no evaluator built-ins

### Phase 4 — Query CLI (pulse_cli)

- [x] `pulse info` / `scope` / `signal` / `value` / `property` / `skill`
- [x] `property --event` definitions + `--eval` cross-module composition
- [x] `--max` (post-eval truncation), `--count` (match counts)
- [x] `--cycles` window guidance documented in the pulse skill
- [ ] ~~`query` / `timeline`~~ — rejected: primitives + pipe composition
  (timeline = property's `{from,to}` list; kept out to keep the CLI surface
  minimal)

### Phase 5 — nzea integration

- [x] `nzea_cache/cache.pulse`, `nzea_core/core.pulse`, `nzea_core/bp.pulse`
  event files (added on demand — 用到再说 principle)
- [ ] Module-level event files under `events/` subdirectories (not needed so
  far; per-module `*.pulse` files at module root serve the same purpose)
- [x] `pulse skill` command packages the agent skill in the binary
- [x] `pulse` skill installed at `.agents/skills/pulse/`
- [x] `nzea-debug-loop` skill updated to use pulse
- [x] `wavepeek` skill retired; wavepeek submodule removed

## Design decisions captured

| Decision | Rationale |
|----------|-----------|
| No scope in `.pulse` file | Same events file reusable across tile/core/fpga hierarchies; scope bound via `--event SCOPE` (supports `*` unique-match) |
| Temporal operators are composable | They reduce to per-cycle booleans; `(a -> b) && c` is valid — supersedes the early "terminal" design |
| Full expression engine | Parser (winnow) + AST + generic walkers (`walk::map`/`visit`); all passes are node-level callbacks |
| Stdlib as `.pulse` templates | Text substitution before parsing; parameters may sit in any syntactic position; evaluator knows no built-in functions (`&&` aliases are noise) |
| History sampling via `->n` | `x ->1 1` reads one cycle back; no `prev` function — the window operator `n--m` covers ranges |
| Window operator `n--m` | One origin semantics: A is the origin, B within [n before, m after]; `--n`/`n--` are degenerate forms; lookahead is free on a loaded waveform |
| Single Rust crate per layer | `pulse_wave` (library) + `pulse_cli` + `pulse_macro` |
| JSON output on demand | `--json` flag on every command; text output stays the default for humans |
| Search speed | Strong `--cycles` window is the primary lever; `--max` only truncates afterwards |
