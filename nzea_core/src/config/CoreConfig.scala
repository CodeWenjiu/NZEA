package nzea_core.config

import mainargs.{ParserForClass, TokensReader}
import nzea_core.isa.Isa

/** Core-specific configuration used by nzea_core modules. No defaults here (rule 6): every construction site passes
  * explicit values, so the compiler forces each call site to state its full configuration.
  *
  * Deliberately minimal: microarchitecture knobs (engine selection, queue/ROB depths, vector sizing, BPU sizing) return
  * incrementally with the features that need them, and are to be redesigned without assuming the old shapes. What the
  * design is generated *for* is not configuration — it is a [[nzea_rtl.RtlFlow]], and only the modules whose RTL
  * changes with it take one.
  */
case class CoreConfig(isa: Isa, defaultPc: Long):

    /** Data/address width derived from ISA (e.g. riscv32 -> 32, riscv64 -> 64). */
    val width: Int = isa.xlen.bits

object CoreConfig:

    given upickle.default.ReadWriter[CoreConfig] = upickle.default.macroRW

    /** The generator framework's reader: a structure that embeds this config (`TopConfig`) exposes it as its fields'
      * flags (`--isa`, `--defaultPc`), and the config file carries it nested in that structure.
      */
    given TokensReader[CoreConfig] = ParserForClass[CoreConfig]
