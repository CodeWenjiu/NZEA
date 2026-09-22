package nzea_cli

import mainargs.arg
import nzea_config.core.CacheConfig
import nzea_config.ElaborationTarget
import nzea_config.FpgaBoard
import nzea_config.PlatformConfig
import nzea_config.SynthPlatform
import nzea_config.tile.TileConfig
import nzea_config.core.CoreConfig

/** Flat CLI arguments for backward-compatible command-line flags. */
case class CliArgs(
    @arg(doc =
      "Verilog output directory (overrides default build/<target>/<platform>/<isa>/<sim|sta> when set)"
    ) outDir: Option[String] = None,
    @arg(doc = "Elaboration hierarchy: core (Top) or tile (NzeaTile)") target: ElaborationTarget =
      ElaborationTarget.Core,
    @arg(doc = "Backend platform segment (e.g. yosys, fpga)") platform: String = "yosys",
    @arg(doc = "If true, emit simulation RTL (DPI bridges); if false, emit synthesizable top-level IO") sim: Boolean =
      true,
    @arg(doc = "ISA string, e.g. riscv32i (underscore-named extensions; order after `_` ignored)") isa: String =
      "riscv32i",
    @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L,
    @arg(doc = "Tile clock frequency in Hz (sets UART divisor etc.)") clockHz: Int = 1_000_000_000,
    @arg(doc = "FPGA board target (lxb_artix7, tangnano20k)") fpgaBoard: String = "lxb_artix7",
    @arg(doc = "I-Cache set count (cache geometry) ") cacheNSets: Int = 16,
    @arg(doc = "I-Cache associativity (ways)") cacheNWays: Int = 8,
    @arg(doc = "I-Cache line width in bits") cacheLineBits: Int = 32,
    @arg(doc = "Per-slave outstanding transactions (bus fabric)") perSlaveOutstanding: Int = 8,
    @arg(doc =
      "Instantiate the ILA debug wrapper in the fpga board (arm/capture via just ila-capture)"
    ) debugIla: Boolean = false
) {

  val synthPlatform: SynthPlatform = SynthPlatform.fromString(platform).getOrElse(SynthPlatform.Yosys)
  val fpgaBoard_ : FpgaBoard = FpgaBoard.fromString(fpgaBoard).getOrElse(FpgaBoard.LxbArtix7)

  /** Core microarchitecture config assembled from CLI args — the single source of truth for every CLI-driven flow (tile
    * / core / fpga).
    */
  def coreConfig: CoreConfig =
    CoreConfig(
      isa = isa,
      defaultPc = defaultPc,
      sim = sim
    )

  /** Tile-level I-Cache config from CLI args; None would bypass the cache. */
  def cache: Option[CacheConfig] =
    Some(CacheConfig(nSets = cacheNSets, nWays = cacheNWays, lineBits = cacheLineBits))

  def tileConfig: TileConfig =
    TileConfig(
      platform = synthPlatform match {
        case SynthPlatform.Yosys => PlatformConfig.Yosys
        case SynthPlatform.Fpga  => PlatformConfig.Fpga(extMemBuses = fpgaBoard_.extMemBuses)
      },
      clockHz = clockHz,
      cache = cache,
      perSlaveOutstanding = perSlaveOutstanding
    )

  /** `dpi` or `hw` under `build/<target>/<platform>/<isa>/`. */
  def rtlFlowSegment: String = if (sim) "dpi" else "hw"

  /** firtool options for current [[sim]] mode. */
  def firtoolOpts: Array[String] = synthPlatform.firtoolOpts(sim)

  /** Default and override-aware RTL output directory. */
  val effectiveOutDir: String = target match {
    case ElaborationTarget.Fpga =>
      outDir.getOrElse(s"build/fpga/${fpgaBoard_.segment}/${coreConfig.isa}/hw")
    case _ =>
      outDir.getOrElse(
        s"build/${target.segment}/${synthPlatform.segment}/${coreConfig.isa}/${rtlFlowSegment}"
      )
  }

}