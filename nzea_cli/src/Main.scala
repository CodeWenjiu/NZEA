package nzea_cli

import mainargs.ParserForClass
import nzea_config.NzeaConfig
import nzea_core.Elaborate

object Main {
  def main(args: Array[String]): Unit = {
    implicit val config = ParserForClass[NzeaConfig].constructOrExit(args.toIndexedSeq)
    Elaborate.elaborate
  }
}
