package nzea_cli

import mainargs.ParserForClass
import nzea_config.{ElaborationTarget, NzeaConfig}
import nzea_core.CoreElaborate
import nzea_tile.TileElaborate

object Main {
  def main(args: Array[String]): Unit = {
    implicit val config = ParserForClass[NzeaConfig].constructOrExit(args.toIndexedSeq)
    config.target match {
      case ElaborationTarget.Tile => TileElaborate.elaborate
      case ElaborationTarget.Core => CoreElaborate.elaborate
    }
  }
}
