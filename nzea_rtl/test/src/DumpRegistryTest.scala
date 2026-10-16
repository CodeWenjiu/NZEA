package nzea_rtl

import utest.*

/** What a dump writes because the design used it.
  *
  * The registry is what keeps the CLI out of the business of knowing which parts a design has: a part registers as it
  * is instantiated, and the dump asks for what was registered. Three rules make that work, and all three are the
  * registry's own bookkeeping rather than any module's — one emitter per key (a module used many times, or a level
  * elaborated twice in one JVM, still writes once), first use is the order, and a reset empties the list so the next
  * dump starts from nothing.
  */
object DumpRegistryTest extends TestSuite:

    val tests = Tests:
        test("emitters register as parts are used, and a dump writes them in that order"):
            DumpRegistry.reset()
            val wrote = scala.collection.mutable.ArrayBuffer.empty[String]
            DumpRegistry.onEmit("first")(_ => wrote += "first")
            DumpRegistry.onEmit("second")(_ => wrote += "second")
            // The same part used again: the emitter it registered first is the one the dump keeps.
            DumpRegistry.onEmit("first")(_ => wrote += "first again")
            DumpRegistry.emitAll(os.pwd)
            assert(wrote.toSeq == Seq("first", "second"))

        test("a part the design did not use writes nothing, and a reset forgets the parts it did"):
            DumpRegistry.reset()
            val wrote = scala.collection.mutable.ArrayBuffer.empty[String]
            DumpRegistry.onEmit("unused")(_ => wrote += "unused")
            DumpRegistry.reset()
            val dir = os.temp.dir(prefix = "nzea-dump-registry-test")
            try
                DumpRegistry.emitAll(dir)
                assert(wrote.isEmpty)
                assert(os.list(dir).isEmpty)
            finally os.remove.all(dir)
