package nzea_rtl

import mainargs.TokensReader
import utest.*

/** Scope contract: the path a parent hands a child is what the names and the schema's `source` are built from. */
object StatScopeTest extends TestSuite:

    private val reader = summon[TokensReader[StatScope]]

    val tests = Tests:
        test("a child appends its own segment"):
            assert(StatScope.root.path.isEmpty)
            assert(StatScope.root.child("core").child("middle").path == Seq("core", "middle"))

        test("the flag spells the path in dots, or `root` for the design's own scope"):
            assert(reader.read(Seq("root")) == Right(StatScope.root))
            assert(reader.read(Seq("core.back.int")) == Right(StatScope(Seq("core", "back", "int"))))
            assert(reader.read(Seq.empty).isLeft)

        test("the scope round-trips through the parameter JSON"):
            val scope = StatScope(Seq("core", "middle"))
            val text = upickle.default.write(scope)
            assert(upickle.default.read[StatScope](text) == scope)
