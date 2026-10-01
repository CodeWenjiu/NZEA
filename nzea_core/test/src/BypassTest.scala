package nzea_core

import mainargs.TokensReader
import utest.*

/** The bypass declaration's contracts: the level token — one spelling for the config file and the generator's flag —
  * and the predicates a consumer asks, so the level that names no link answers `false` to all of them.
  */
object BypassTest extends TestSuite:

    val tests = Tests:
        test("every level round-trips through the file and the token"):
            for level <- BypassLevel.values do
                assert(BypassLevel.parseOrThrow(level.name) == level)
                assert(upickle.default.read[BypassLevel](upickle.default.write(level)) == level)

        test("the flag reader takes the same token"):
            val reader = summon[TokensReader[BypassLevel]]
            for level <- BypassLevel.values do assert(reader.read(Seq("--bypass", level.name)) == Right(level))

        test("a deeper level implies the shallower link"):
            assert(!BypassLevel.None.commit)
            assert(!BypassLevel.Commit.execute)
            assert(BypassLevel.Commit.commit)
            assert(BypassLevel.Execute.commit && BypassLevel.Execute.execute)

        test("unknown levels are rejected, and the old spellings with them"):
            assert(BypassLevel.parse("wb").isLeft)
            assert(BypassLevel.parse("writeback").isLeft)
            assert(BypassLevel.parse("none") == Right(BypassLevel.None))
            assert(BypassLevel.parse("COMMIT") == Right(BypassLevel.Commit))
            assert(BypassLevel.parse("execute") == Right(BypassLevel.Execute))

        test("the report names the links that reach the read"):
            assert(BypassLevel.None.report == "none")
            assert(BypassLevel.Commit.report == "commit -> register read")
            assert(BypassLevel.Execute.report == "execute -> register read")
