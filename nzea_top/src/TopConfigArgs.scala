package nzea_top

import mainargs.{ParserForClass, TokensReader}

case class TopConfigArgs(
    core: CoreConfigArgs
)

object TopConfigArgs:
    given TokensReader[TopConfigArgs] = ParserForClass[TopConfigArgs]
