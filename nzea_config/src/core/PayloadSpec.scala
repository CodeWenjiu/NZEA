package nzea_config.core

/** Data-path information units.
  *
  * Data-path payloads (IQ entries, BRU inputs, ROB slots, commit messages, ...) used to carry a fixed field set
  * regardless of configuration. Whether a field actually has a consumer depends on config (e.g. RAS disabled →
  * `is_ret`/`is_call`/`rd_index` have no consumer). This registry is the single place that decides "which fields exist
  * under which config"; payload bundles embed the units as `Option[Data]` fields so the field is removed structurally
  * (ports, registers and muxes all shrink — firtool DCE alone cannot shrink top-level ports).
  */
object PayloadSpec {

  sealed trait Unit

  /** `is_ret` on the execution chain: BRU ret-statistics counters (sim) and ret classification on the way to the BRU
    * (RAS).
    */
  case object RetExec extends Unit

  /** `is_ret` on the commit chain: RAS pop (a committed ret pops exactly once). */
  case object RetCommit extends Unit

  /** `is_call` + `rd_index` on the execution chain: RAS push classification. */
  case object CallExec extends Unit

  /** A unit exists in a payload iff at least one of its consumers is enabled. */
  def enabled(u: Unit)(implicit c: CoreConfig): Boolean = u match {
    case RetExec   => c.sim || c.bpu.rasDepth.isDefined
    case RetCommit => c.bpu.rasDepth.isDefined
    case CallExec  => c.bpu.rasDepth.isDefined
  }

}
