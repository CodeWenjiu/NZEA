# Configuration Aggregation Policy (design note, future plan)

Status: **design only** — recorded as a guideline for future refactors. The
`BpuConfig` split (this commit) is the reference example.

## Background

`BpuConfig` (phtSize, btbSize, rasDepth) was split out of the flat
`CoreConfig` parameter list because the BPU is a self-contained subsystem
with its own sizing decisions and an on/off switch (`rasDepth: Option[Int]`).
`CacheConfig` (tile level) is an earlier precedent. This note records when —
and when not — to repeat that split.

## When to aggregate (all four conditions)

A group of parameters deserves its own config type iff:

| Criterion | Meaning | `BpuConfig` example |
|---|---|---|
| Cohesion | parameters are consumed by one subsystem only | pht/btb/ras are consumed by the BPU only |
| Self-contained | the type can own its requires/derived vals | `require(power of 2)`, future `ghrBits` derivation |
| Passed as a unit | the subsystem can receive the config as a whole | IFU reads `config.bpu.*`; may later take `(implicit bpu: BpuConfig)` |
| Enough parameters | ~4+ parameters; below that the split is ceremony | currently 3, borderline |

## When NOT to aggregate

The flat core of `CoreConfig` stays flat on purpose: structural parameters
(`robDepth`, `issueQueueDepth`, `prfDepth`, ...) feed **interleaved derived
vals** shared across modules:

```scala
val prfAddrWidth = ...prfDepth...    // IDU/ISU/IQ/PRF
val lsqIdWidth   = ...robDepth/2...  // LSQ/LSU/Rob
val iqIdWidth    = ...issueQueueDepth...
```

Splitting them scatters the derivation logic; `CoreConfig` exists precisely
to keep flat parameters + derived vals in one place.

## Target shape (mixed mode)

```
CoreConfig (flat structural params + derived vals)
 ├── isa / robDepth / prfDepth / ...     ← interleaved, stays flat
 ├── bpu: BpuConfig                      ← self-contained subsystem ✓
 └── (future) cache: CacheConfig         ← precedent exists ✓
```

## Future candidates (only when parameter count grows)

- **LSU / memory subsystem**: ls-queue depth, outstanding policies — if
  policies multiply.
- **Vector backend**: vlen / vrfDepth / viqDepth are fairly cohesive.
- **New predictors** (TAGE, ...): extend `BpuConfig` rather than new types.

## Principles

1. Split when a group is a **self-contained subsystem with enough knobs** —
   never for decoration (YAGNI; <4 parameters stay flat).
2. A split is a breaking change for every construction site (rule: no
   defaults outside the CLI layer) — the compiler enforces the update, which
   is exactly why the split is safe.
3. Prefer extending an existing sub-config over creating a new one
   (`BpuConfig` grows with new predictors).
