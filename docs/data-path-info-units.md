# Data-Path Information Units (design note, not yet implemented)

Status: **design only** — recorded for future implementation. Do not implement
in the current commit.

## Motivation

Data-path payloads (IQ entries, BRU inputs, ROB slots, `CommitMsg`, ...)
currently carry a fixed set of fields. Whether a field is actually needed
depends on configuration (e.g. RAS disabled → `is_ret`/`is_call`/`rd_index`
have no consumer). Today this is handled passively: firtool's DCE removes
unconsumed fields inside the hierarchy, but **cannot shrink top-level ports**
(e.g. `CommitMsg` exposed on `TileTopIO` keeps `is_ret` even with RAS
disabled — verified empirically in the `rasDepth=None` dump).

We want the field set of each payload to be **actively derived from
configuration**, so that:

- disabled features do not carry their fields anywhere (including top ports),
- shared information is transmitted **exactly once** (no per-feature
  duplicates),
- adding a new feature only declares *which existing units it consumes*,
  never re-defines fields.

## Core abstraction: atomic information units

A unit is an independent, named `Bundle` whose fields have concrete meaning.
Units are defined **independently of the features that consume them** — this
is what resolves overlap: `is_ret` is defined once (in `RetUnit`) and shared
by every consumer (RAS pop, ret statistics).

```scala
// Information units — one Bundle per unit, fields are named and typed.
class RetInfo  extends Bundle { val is_ret  = Bool() }
class CallInfo extends Bundle { val is_call = Bool(); val rd_index = UInt(5.W) }
```

## Registration: unit → consumers, enabled = any consumer

A central registry maps each unit to the set of *features* that consume it.
A unit exists in a payload iff at least one of its consumers is enabled by
configuration. This is the single place where "which fields under which
config" is decided; adding a feature means adding one entry here.

```scala
// nzea_core/src/config/PayloadSpec.scala (future)
sealed trait PayloadUnit
case object RetUnit  extends PayloadUnit
case object CallUnit extends PayloadUnit

object PayloadSpec {
  private def consumers(u: PayloadUnit)(implicit c: CoreConfig): Set[Boolean] = u match {
    case RetUnit  => Set(c.sim, c.bpu.rasDepth.isDefined) // stat counters + RAS
    case CallUnit => Set(c.bpu.rasDepth.isDefined)        // RAS only
  }
  def enabled(u: PayloadUnit)(implicit c: CoreConfig): Boolean = consumers(u).exists(identity)
}
```

## Channel payloads: Option[Data] fields

Each data-path payload (exec chain, commit chain, top port, ...) embeds the
units that are enabled for it as `Option[Data]` fields. `None` removes the
field at elaboration time — the payload is *structurally* narrower (ports,
ROB slots, Mux networks all shrink), not just DCE'd.

```scala
class ExecPayload(implicit c: CoreConfig) extends Bundle {
  val pc   = UInt(32.W)
  val ret  = if (PayloadSpec.enabled(RetUnit))  Some(new RetInfo)  else None
  val call = if (PayloadSpec.enabled(CallUnit)) Some(new CallInfo) else None
}
```

## Consumer pattern

```scala
ras.io.pop  := payload.ret.map(_.is_ret).getOrElse(false.B)
stats_en    := io.in.valid && payload.ret.map(_.is_ret).getOrElse(false.B)
```

## Proposed mapping for nzea (first cut)

| Unit / field | Payloads | Consumers | Exists iff |
|---|---|---|---|
| `RetUnit.is_ret` (commit chain) | RobEnqPayload, RobEntry, RobCommitPayload, CommitMsg | RAS pop | `rasDepth.isDefined` |
| `RetUnit.is_ret` (exec chain) | IDUOut, IQ entry, BruInput | BRU ret stats | `sim \|\| rasDepth.isDefined` |
| `CallUnit.is_call` | BpUpdate, BruS1Out | RAS push | `rasDepth.isDefined` |
| `CallUnit.rd_index` | IDUOut, IQ entry, BruInput | is_call computation | `rasDepth.isDefined` |
| `ras_update` port | IFU, Core | RAS pop | `rasDepth.isDefined` |

The exec/commit distinction above is only about *where* a unit is carried;
the unit itself stays shared. If channels multiply later, a `Channel` set per
unit can be added to the registry.

## Field ownership: why each field exists

Before parameterizing, record *who each field serves* — this determines its
existence condition:

| Field (copy) | Purpose | Nature | Exists iff |
|---|---|---|---|
| `rd_index` (exec chain: IDUOut → IQ entry → BruInput) | input to the `is_call` computation at BRU (`rd == x1`) | **RAS functional input** (the `IDUOut.rd_index` copy predates RAS — it feeds commit/DPI; only the exec copy is RAS-driven) | `rasDepth.isDefined` |
| `is_ret` (commit chain: IDUOut → ROB slot → CommitMsg → `ras_update`) | triggers the RAS **pop** | **RAS functional input** | `rasDepth.isDefined` |
| `is_ret` (exec chain: IDUOut → IQ entry → BruInput) | feeds the ret stats counters (`stat_bp_ret`/`ret_mispred`) | **observability, not RAS** — exists to evaluate RAS effectiveness, and must survive even when RAS is disabled (`sim=true`) so the no-RAS ret baseline stays measurable | `sim \|\| rasDepth.isDefined` |

Takeaway: `is_ret` is a neutral *instruction attribute* (JALR with
`rs1==x1, rd!=x1`) consumed by two features (RAS pop, ret statistics). The
exec-chain copy is not redundancy — it is two real consumers sharing one
attribute; the copy disappears exactly when *both* consumers are disabled.

## Explicitly out of scope

- No Scala macros / reflective bundle generation — dynamic field names cost
  type safety; `Option[Data]` + the registry is enough.
- No per-feature bundles that duplicate shared fields (that would recreate
  the redundancy this design removes).
- No channel framework yet — only 2–3 channels exist; hardcode `enabled()`
  calls per bundle until channels grow.

## Rollout plan (when implemented)

1. Add `PayloadSpec` with `RetUnit`/`CallUnit`.
2. Convert `BpUpdate`, `BruInput`, `IDUOut`, `IQ entry`, `RobEnqPayload`,
   `RobEntry`, `CommitMsg`, `RasUpdate` to unit fields.
3. Option-ify consumers.
4. Verify with `rasDepth=None, sim=false` dump that the `commit_msg` top
   port actually narrows (the one place DCE cannot help).
