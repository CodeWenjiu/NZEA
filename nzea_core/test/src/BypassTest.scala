package nzea_core

import mainargs.TokensReader
import utest.*

/** The bypass declaration's contracts: the flag token, the config file's spelling of it, and what each configuration
  * says about the design. The token and the file agree on purpose — one spelling everywhere — and the predicates are
  * what a consumer asks, so a configuration that names no stage has to answer `false` to all of them.
  */
object BypassTest extends TestSuite:

  val tests = Tests:
    test("a configuration round-trips through the flag and the config file"):
      for config <- Seq(BypassConfig.none, BypassConfig.writeback) do
        assert(BypassConfig.parseOrThrow(config.name) == config)
        assert(upickle.default.read[BypassConfig](upickle.default.write(config)) == config)

    test("the flag reader takes the same token"):
      val reader = summon[TokensReader[BypassConfig]]
      for config <- Seq(BypassConfig.none, BypassConfig.writeback) do
        assert(reader.read(Seq("--bypass", config.name)) == Right(config))

    test("only the stages a configuration names answer yes"):
      assert(!BypassConfig.none.writeback)
      assert(BypassConfig.writeback.writeback)

    test("unknown stages are rejected, none is the empty set"):
      assert(BypassConfig.parse("wb").isLeft)
      assert(BypassConfig.parse("execute").isLeft)
      assert(BypassConfig.parse("none") == Right(BypassConfig.none))
      assert(BypassConfig.parse("WRITEBACK") == Right(BypassConfig.writeback))

    test("the report names every link the design has"):
      assert(BypassConfig.none.report == "none")
      assert(BypassConfig.writeback.report == ForwardStage.Writeback.link)
