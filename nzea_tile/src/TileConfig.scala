package nzea_tile

import nzea_config.{CacheConfig, ElaborationTarget, FpgaBoard, SynthPlatform}
import nzea_core.config.CoreConfig

/** Tile-level hardware generation parameters. Wraps [[CoreConfig]] and adds tile/SoC-level options. */
case class TileConfig(
    debug: Boolean = false,
    outDir: Option[String] = None,
    target: ElaborationTarget = ElaborationTarget.Core,
    synthPlatform: String = "yosys",
    sim: Boolean = true,
    clockHz: Int = 1_000_000_000,
    fpgaBoard: String = "lxb_artix7",
    core: CoreConfig = CoreConfig(),
    cache: Option[CacheConfig] = Some(CacheConfig()),
    perSlaveOutstanding: Int = 8
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Yosys)
  val fpgaBoard_ : FpgaBoard = FpgaBoard.fromString(fpgaBoard).getOrElse(FpgaBoard.LxbArtix7)

  /** `dpi` or `hw` under `build/<target>/<platform>/<isa>/`. */
  def rtlFlowSegment: String = if (sim) "dpi" else "hw"

  /** firtool options from [[platform]] for current [[sim]] mode. */
  def firtoolOpts: Array[String] = platform.firtoolOpts(sim)

  /** Default and override-aware RTL output directory. */
  val effectiveOutDir: String = target match {
    case ElaborationTarget.Fpga =>
      outDir.getOrElse(s"build/fpga/${fpgaBoard_.segment}/${core.isa}/hw")
    case _ =>
      outDir.getOrElse(s"build/${target.segment}/${platform.segment}/${core.isa}/${rtlFlowSegment}")
  }

}
