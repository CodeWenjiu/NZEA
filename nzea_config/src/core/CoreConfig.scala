package nzea_config.core

/** Core-specific configuration used by nzea_core modules. No defaults here (rule 6): every construction site passes
  * explicit values, so the compiler forces each call site to state its full configuration.
  *
  * Deliberately minimal: microarchitecture knobs (engine selection, queue/ROB depths, vector sizing, BPU sizing) return
  * incrementally with the features that need them, and are to be redesigned without assuming the old shapes. What the
  * design is generated *for* is not configuration — it is a [[nzea_config.RtlFlow]], and only the modules whose RTL
  * changes with it take one.
  */
case class CoreConfig(isa: String, defaultPc: Long):

  /** Parsed ISA config, used to match extensions (e.g. `isaConfig.hasM`). */
  val isaConfig: IsaConfig = IsaConfig.parseOrThrow(isa)

  /** Data/address width derived from ISA (e.g. riscv32 -> 32, riscv64 -> 64). */
  val width: Int = isaConfig.xlen
