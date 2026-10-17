package nzea_top

import mainargs.{ParserForClass, TokensReader}
import nzea_core.config.CoreConfig

/** The top's configuration tree: what a level top is built as. Today every top is one core — the tree is the core's own
  * [[CoreConfig]] and nothing else; a top that carries more arrives as another member of this shape, and everything that
  * travels with the tree (the generator framework's readers, the config file's round trip) is declared beside it.
  */
case class TopConfig(core: CoreConfig)

object TopConfig:

    given upickle.default.ReadWriter[TopConfig] = upickle.default.macroRW

    /** The generator framework's reader: the tree parses as its fields' flags (`--isa`, `--defaultPc`), flattened into
      * the level's own command line.
      */
    given TokensReader[TopConfig] = ParserForClass[TopConfig]
