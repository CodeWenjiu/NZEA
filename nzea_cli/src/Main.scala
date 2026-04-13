package nzea_cli

import mainargs.ParserForClass
import nzea_config.{ElaborationTarget, NzeaConfig}
import nzea_core.CoreElaborate
import nzea_tile.TileElaborate

object Main {
  def main(args: Array[String]): Unit = {
    val cliArgs = ParserForClass[CliArgs].constructOrExit(args.toIndexedSeq)
    implicit val config: NzeaConfig = cliArgs.toConfig
    config.target match {
      case ElaborationTarget.Tile => TileElaborate.elaborate
      case ElaborationTarget.Core => CoreElaborate.elaborate
    }
  }
}
