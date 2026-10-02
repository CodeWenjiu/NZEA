package nzea_rtl

import nzea_rtl.stat.*
import utest.*

/** Black-box contract of one counter's box, and the naming the emitter and the instantiating module have to agree on.
  */
object StatBoxTest extends TestSuite:

    private val Region = StatRegion("test", "test region")
    private val Cycle = StatCounter("stat_core_middle_cycle", 32, "middle", Region, "cycle count")

    val tests = Tests:
        test("verilog generation: module header, the named register and the write port"):
            val v = StatBox.verilog("StatBox_abc", Cycle)
            assert(v.contains("module StatBox_abc("))
            assert(v.contains("input clock") && v.contains("input resetN"))
            assert(v.contains("input en"))
            assert(v.contains(s"input [${StatBox.Width - 1}:0] data"))
            assert(v.contains(s"output [${StatBox.Width - 1}:0] value"))
            assert(v.contains("reg [31:0] stat_core_middle_cycle /*verilator public_flat_rd*/;"))
            assert(v.contains("assign value = stat_core_middle_cycle;"))
            assert(v.contains("if (!resetN) stat_core_middle_cycle <= '0;"))
            assert(v.contains("else if (en) stat_core_middle_cycle <= data;"))

        test("the box module is named after the counter, and only that counter"):
            assert(StatRegistry.boxModuleName(Cycle) == Cycle.name)
            assert(
                StatRegistry.boxModuleName(Cycle) !=
                    StatRegistry.boxModuleName(Cycle.copy(name = "stat_core_middle_inst_commit"))
            )

        test("emit writes each box named after its module and appends it to the file list"):
            val dir = os.temp.dir(prefix = "nzea-stats")
            os.write.over(dir / "filelist.f", "Middle.sv")
            try
                StatRegistry.reset()
                StatRegistry.declare(Cycle)
                StatRegistry.declare(Cycle.copy(name = "stat_core_middle_inst_commit"))
                val names = StatRegistry.emitBoxes(dir)
                val moduleNames = StatRegistry.declaredCounters.map(StatRegistry.boxModuleName)
                assert(names == moduleNames.map(_ + ".sv"))
                StatRegistry.declaredCounters.zip(moduleNames).foreach { (counter, moduleName) =>
                    assert(os.read(dir / s"$moduleName.sv") == StatBox.verilog(moduleName, counter))
                }
                assert(os.read(dir / "filelist.f") == (Seq("Middle.sv") ++ names).mkString("", "\n", "\n"))
            finally
                StatRegistry.reset()
                os.remove.all(dir)
