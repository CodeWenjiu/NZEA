package nzea_config.core

/** Branch-prediction unit configuration: PHT/BTB sizing and RAS depth. Carried as `CoreConfig.bpu` and read by the
  * fetch-side predictors (IFU/PHT/BTB/RAS) and their update paths (BRU). No defaults here: the CLI entry layer must
  * provide concrete values at every construction site.
  */
case class BpuConfig(
    phtSize: Int,
    btbSize: Int,
    /** Return address stack depth; None disables RAS ret-target prediction. */
    rasDepth: Option[Int]
) {
  require(phtSize > 0 && (phtSize & (phtSize - 1)) == 0, "phtSize must be a power of 2")
  require(btbSize > 0 && (btbSize & (btbSize - 1)) == 0, "btbSize must be a power of 2")
  rasDepth.foreach(d => require(d > 0 && (d & (d - 1)) == 0, "rasDepth must be a power of 2"))
}

object BpuConfig {

  /** Typical BPU sizing shared by every non-tuned flow (CLI defaults, sim, tests). Single source of truth: flows that
    * need the standard BPU reference this instead of repeating literal values.
    */
  val typical: BpuConfig = BpuConfig(phtSize = 64, btbSize = 16, rasDepth = Some(8))
}
