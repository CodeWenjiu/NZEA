package nzea_top

import mainargs.{ParserForClass, TokensReader}

/** `TopConfig` as flags: the tree's shape, today one core. */
case class TopConfigArgs(
    core: CoreConfigArgs
)

object TopConfigArgs:
    given TokensReader[TopConfigArgs] = ParserForClass[TopConfigArgs]
