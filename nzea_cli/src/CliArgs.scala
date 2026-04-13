package nzea_cli

import mainargs.arg
import nzea_config.{CoreConfig, ElaborationTarget, NzeaConfig}

/** Flat CLI arguments for backward-compatible command-line flags. */
case class CliArgs(
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory (overrides default build/<target>/<platform>/<isa>/<sim|sta> when set)") outDir: Option[String] = None,
  @arg(doc = "Elaboration hierarchy: core (Top) or tile (NzeaTile)") target: ElaborationTarget = ElaborationTarget.Core,
  @arg(doc = "Backend platform segment (e.g. yosys)") synthPlatform: String = "yosys",
  @arg(doc = "If true, emit simulation RTL (DPI bridges); if false, emit synthesizable top-level IO") sim: Boolean = true,
  @arg(doc = "ISA string, e.g. riscv32i or riscv32im_zve32x_zvl128b (underscore-named extensions; order after `_` ignored)") isa: String = "riscv32i",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L,
  @arg(doc = "Rob depth (number of in-flight entries)") robDepth: Int = 16,
  @arg(doc = "Integer issue queue depth (entries between ISU and execution cluster)") issueQueueDepth: Int = 4,
  @arg(doc = "Physical register file depth (for rename)") prfDepth: Int = 64,
  @arg(doc = "Vector register width in bits when ISA has no zvl*N*b token (fallback VLEN)") vlen: Int = 128,
  @arg(doc = "Physical vector register file depth / PVR capacity (rename targets)") vrfDepth: Int = 64,
  @arg(doc = "Vector issue queue depth (RVV)") viqDepth: Int = 8,
  @arg(doc = "PHT size (power of 2)") phtSize: Int = 64,
  @arg(doc = "BTB size (power of 2)") btbSize: Int = 16
) {
  def toConfig: NzeaConfig =
    NzeaConfig(
      debug = debug,
      outDir = outDir,
      target = target,
      synthPlatform = synthPlatform,
      sim = sim,
      core = CoreConfig(
        isa = isa,
        defaultPc = defaultPc,
        robDepth = robDepth,
        issueQueueDepth = issueQueueDepth,
        prfDepth = prfDepth,
        vlen = vlen,
        vrfDepth = vrfDepth,
        viqDepth = viqDepth,
        phtSize = phtSize,
        btbSize = btbSize
      )
    )
}
