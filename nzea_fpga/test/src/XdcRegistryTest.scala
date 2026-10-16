package nzea_fpga

import utest.*

import nzea_rtl.DumpRegistry

/** The constraint registry's own semantics: one file per owner, the first registration is the one, a dump that used
  * nothing writes nothing, and the state clears with the rest of a dump's. What the *board's* constraints say is not
  * this test's business — the elaborate test checks them against the pins the top declares.
  */
object XdcRegistryTest extends TestSuite:

    /** What a dump of the current registry writes, as file name -> text. */
    private def files: Map[String, String] =
        val dir = os.temp.dir(prefix = "nzea-xdc")
        try
            DumpRegistry.emitAll(dir)
            os.list(dir).map(entry => entry.last -> os.read(entry)).toMap
        finally os.remove.all(dir)

    val tests = Tests:

        test("an owner writes one file, and its first registration is the one"):
            DumpRegistry.reset()
            XdcRegistry.onEmit("a")("first")
            XdcRegistry.onEmit("a")("second")
            XdcRegistry.onEmit("b")("other")
            val written = files
            // One file per owner, listed in first-use order for the project generator.
            assert(written.keySet == Set("a.xdc", "b.xdc", "xcdfiles.f"))
            assert(written("xcdfiles.f") == "a.xdc\nb.xdc\n")
            // The text is the owner's behind the generated marker, and an owner is its first registration.
            assert(written("a.xdc").contains("do not edit"))
            assert(written("a.xdc").endsWith("first\n"))
            assert(!written("a.xdc").contains("second"))
            assert(written("b.xdc").endsWith("other\n"))

        test("a dump that used nothing with constraints writes no file"):
            DumpRegistry.reset()
            assert(files.isEmpty)
