package nzea_cli

import mainargs.ParserForClass
import nzea_core.{Elaborate, NzeaConfig}

object Main {
  def main(args: Array[String]): Unit = {
    val config = ParserForClass[NzeaConfig].constructOrExit(args.toIndexedSeq)
    Elaborate.elaborate(config)
  }
}
