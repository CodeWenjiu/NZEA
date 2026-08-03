# pulse — Roadmap

pulse is a waveform event engine and query tool for RTL debugging.
It replaces wavepeek with a simpler, layered design: signal queries at the bottom,
named-event composition in the middle, and a minimal query CLI at the top.

## Architecture

```
┌─────────────────────────────────────────┐
│  Query layer   (pulse_cli)              │
│  $ pulse query --def cache.events       │
│      --scope TOP.NzeaTile.icache        │
│      --waves tb.fst --select miss       │
└──────────────────┬──────────────────────┘
                   │ resolves named events
┌──────────────────▼──────────────────────┐
│  Event layer    (events/*.events)        │
│  YAML files per RTL module.             │
│  Composes boolean + temporal events.    │
└──────────────────┬──────────────────────┘
                   │ compiles to atomic properties
┌──────────────────▼──────────────────────┐
│  Engine layer   (pulse_engine)          │
│  Opens FST/VCD, runs property queries,  │
│  temporal post-processing, JSON output. │
└─────────────────────────────────────────┘
```

## Phases

### Phase 1 — Engine (pulse_engine)

- [ ] Open FST/VCD waveforms (wellen backend)
- [ ] Scope hierarchy discovery (`info`, `scope`, `signal`)
- [ ] Point value sampling at explicit timestamps (`value`)
- [ ] Boolean property match over a time window (`property`)
  - `--on` trigger expression (posedge clk, signal edge)
  - `--eval` boolean condition
  - `--capture match` mode
  - `--max`, `--to end`, clamp support (lessons from wavepeek)
- [ ] JSON output contract (`--json`)
- [ ] Per-module scope-relative signal binding

### Phase 2 — Events (pulse_event)

- [ ] YAML event-definition file format
  ```yaml
  event req_fire = req_valid & req_ready
  event hit      = req_fire & (way0_hit | way1_hit | way2_hit | way3_hit)
  event miss     = req_fire & !hit
  ```
- [ ] Scope applied at query time (`--scope` flag), not in the file
- [ ] Boolean event composition (`&`, `|`, `!`)
- [ ] Cross-module event reference via `import` + namespace prefix
- [ ] Fail-fast on undefined signals (no silent empty results)

### Phase 3 — Temporal events

- [ ] `A -> B` : A followed by B (any cycles later)
- [ ] `A -N> B` : A followed by B exactly N cycles later
- [ ] `A -> B within N` : A followed by B within N cycles
- [ ] Temporal events are terminal (not composable with `&`/`|`)
- [ ] Post-processing layer: two property queries + time-series correlation
  (not per-cycle waveform scanning)

### Phase 4 — Query CLI (pulse_cli)

- [x] `pulse info --waves tb.fst`
- [x] `pulse scope --waves tb.fst --filter '.*cache.*'`
- [x] `pulse signal --waves tb.fst --scope TOP.icache`
- [x] `property` with `--event` definitions + `--eval` cross-module composition
- [x] `property --max N` (post-eval truncation)
- [ ] ~~`query` / `timeline` / `--count`~~ — rejected: primitives + pipe composition
  (count = `| wc -l`, timeline = property's `{from,to}` list; kept out to
  keep the CLI surface minimal)
- [ ] Search-speed guidance: strong `--cycles` window constraint documented in
  skills instead of streaming/early-exit eval optimization

### Phase 5 — nzea integration

- [ ] Events files alongside RTL modules
  - `nzea_cache/events/cache.events`
  - `nzea_core/events/ifu.events`, `lsu.events`, `rob.events`
  - `nzea_rtl/events/crossbar.events`
- [ ] Replace wavepeek in the `nzea-debug-loop` skill
- [ ] Replace wavepeek in the `wavepeek` skill (or retire it)
- [ ] Agent auto-discovers events from module directory layout

## Design decisions captured

| Decision | Rationale |
|----------|-----------|
| No scope in `.events` file | Same events file reusable across tile/core/fpga hierarchies |
| Temporal events are terminal | `A -> B` produces pairs, not a boolean; can't `&` it |
| No expression engine | Wellen provides value changes; bool ops are post-filter |
| Single Rust crate per layer | `pulse_engine`, `pulse_event`, `pulse_cli` + `pulse_macro` |
| JSON output by default | Agent pipeline depends on machine-readable output |
