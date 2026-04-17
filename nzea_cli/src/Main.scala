package nzea_cli

import mainargs.ParserForClass
import nzea_config.{ElaborationTarget, NzeaConfig}
import nzea_core.CoreElaborate
import nzea_core.config.CoreConfig
import nzea_tile.TileElaborate

object Main {
  def main(args: Array[String]): Unit = {
    val cliArgs = ParserForClass[CliArgs].constructOrExit(args.toIndexedSeq)
    val config: NzeaConfig = cliArgs.toConfig
    implicit val coreConfig: CoreConfig = config.core
    config.target match {
      case ElaborationTarget.Tile =>
        TileElaborate.elaborate(
          sim = config.sim,
          outDir = config.effectiveOutDir,
          firtoolOpts = config.firtoolOpts
        )
      case ElaborationTarget.Core =>
        CoreElaborate.elaborate(
          sim = config.sim,
          outDir = config.effectiveOutDir,
          firtoolOpts = config.firtoolOpts
        )
    }
  }
}
