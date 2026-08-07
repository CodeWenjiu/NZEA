# Single-Source Configuration (design note, future plan)

Status: **design only** — survey completed 2026-08-08; implementation deferred
until the boundary decisions below are agreed. Related: `config-aggregation.md`
(which parameter groups deserve their own sub-config type).

## Problem

Editing a config value sometimes produces **no observable change**: the value
is shadowed by another construction site (a default parameter, a hand-built
config in another flow, or a field that is never read). Root cause: the same
config value is **defined in multiple places**, and the effective one is not
obvious. `git grep` cannot tell which definition wins.

## Survey: where the same value is defined today

### 1. `BpuConfig(64, 16, Some(8))` — 5+ definitions

| Site | Role | Effective? |
|---|---|---|
| `nzea_cli/src/CliArgs.scala:50` | CLI layer default (allowed by rule 6) | CLI tile/core/fpga flows |
| `nzea_tile/src/TileConfig.scala:10` (default `core`) | **dead value** — CLI passes `core` explicitly; sim flow uses its own implicit; `NzeaTile` never reads `cfg.core` | never |
| `nzea_fpga/src/FpgaConfig.scala:12` (default `core`) | **dead value** — CLI fpga flow uses `cfg.core` from `CliArgs`; `LxbArtix7Config` extends it but `NzeaTile` ignores `cfg.core` | never |
| `nzea_sim/src/SimElaborate.scala:19` | hand-built `CoreConfig`, independent of CLI | sim flows only |
| `nzea_core/test/...` (BruStatsTest, CoreStatsTest, VectorBackendTest) | test-local | tests only |

### 2. Default parameters outside the CLI layer (rule-6 violation)

- `CoreConfig`: 9 defaults (`isa`, `defaultPc`, `robDepth`, `issueQueueDepth`,
  `prfDepth`, `vlen`, `vrfDepth`, `viqDepth`, `sim`) — `nzea_core/src/config/CoreConfig.scala`
- `TileConfig`: 5 defaults — `nzea_tile/src/TileConfig.scala`
- `FpgaConfig`: 4 defaults — `nzea_fpga/src/FpgaConfig.scala`
- `CacheConfig`: 3 defaults — `nzea_config/src/CacheConfig.scala`
- `Rob.apply` / `class Rob`: `prfAddrWidth: Int = 6` — module-level default,
  implicitly coupled to `prfDepth = 64`

Consequence: `SimElaborate` passes only `isa` + `bpu` and silently inherits
every other `CoreConfig` default, so editing a `CoreConfig` default changes
**only** the sim flow — inconsistent observability across flows.

### 3. `clockHz` — 4 definitions with different defaults

| Site | Default |
|---|---|
| `CliArgs` / `TileConfig` | 1 GHz |
| `FpgaConfig` | 100 MHz |
| `SimElaborate` (tile branch) | 100 MHz |

### 4. Dual-channel config delivery (`cfg.core` vs `implicit config`)

`NzeaTile(cfg: TileConfig)(implicit config: CoreConfig)` reads **only**
`config` (sim, width, robDepth, ...) and `cfg` only for
`synthPlatform/clockHz/cache/perSlaveOutstanding`. `cfg.core` is never read
inside `NzeaTile`; it is consumed only by `Main.scala` (`implicit val
coreConfig = cfg.core`). This is the trap: `TileConfig.core` looks like a
real knob but is a **dead field in the sim and fpga flows**.

## Target architecture

Single source of truth per value, enforced by construction:

```
defaults (only here, CLI layer)
  CliArgs / NzeaConfig   ← the one place with `= default` values
        │
        ▼
case classes without defaults (compiler forces explicit args at every site)
  CoreConfig / TileConfig / FpgaConfig / CacheConfig / BpuConfig
        │
        ▼
consumers (hardware modules) — explicit/implicit passthrough only
```

Rules:

1. **One definition per value.** If a value must be tweakable, it is a CLI
   arg flowing through explicit constructor chains; nothing else defines it.
2. **No defaults outside the CLI layer** (already rule 6 — enforce it by
   deleting the existing defaults; the compiler then points at every site).
3. **No dead config fields.** Any field of a config type must be read
   somewhere. `TileConfig.core` / `FpgaConfig.core` either become the single
   channel (NzeaTile reads `cfg.core` only) or are removed.
4. **Derived defaults live in one factory.** If flows share a "typical"
   config (e.g. BPU sizing), expose it as a factory/constant on the config
   object (e.g. `BpuConfig.typical`), not as constructor defaults repeated at
   call sites.

## Boundary decisions (to discuss)

Proposed as appropriate to keep local:

- **CLI arg defaults** (`CliArgs`) — by rule 6.
- **Test-local configs** — tests construct their own `CoreConfig`; they must
  not depend on product defaults drifting.
- **Board-level constants** (`LxbArtix7Config` clockHz/perSlaveOutstanding) —
  board identity is a valid local definition; keep them as explicit args from
  the board config, not defaults on the shared type.
- **Module-internal constants** — widths that are never configurable across
  flows; keep them `private val` in the module, not config fields.

Proposed to centralize:

- `BpuConfig` typical sizing (currently duplicated in CliArgs/SimElaborate/
  tests).
- `clockHz` per flow (one default per flow, not four).
- `prfAddrWidth` derivation (from `prfDepth` only; remove `Rob.apply`
  default).

## Execution plan (when approved)

1. Delete defaults on `CoreConfig`/`TileConfig`/`FpgaConfig`/`CacheConfig`;
   fix every compile error (compiler-enforced).
2. Resolve the dual channel: either `NzeaTile` consumes `cfg.core` (single
   channel) or remove `TileConfig.core`/`FpgaConfig.core` and let all flows
   pass `CoreConfig` via the implicit channel only.
3. Introduce `BpuConfig.typical` (or similar) and use it in CliArgs +
   SimElaborate + tests.
4. Remove `Rob.apply` default; derive `prfAddrWidth` from `prfDepth` only.
5. Verify: `just dump` (tile/core/fpga), `just iv`, tests — then grep for
   leftover literal configs (`BpuConfig(64`, `prfAddrWidth = 6`, ...).
