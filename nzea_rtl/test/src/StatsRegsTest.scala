package nzea_rtl

import utest.*

/** Black-box Verilog text contract: annotation, read-back and write ports. */
object StatsRegsTest extends TestSuite:

  private val Region = StatRegion("test", "test region")
  private val A = StatCounter("stat_a", 32, Region, "counter a")
  private val B = StatCounter("stat_b", 8, Region, "counter b")
  private val Decls = Seq(A, B)

  val tests = Tests:
    test("verilog generation: module header, clock/reset and per-register ports"):
      val v = StatsRegs.verilog("BpStats", Decls)
      assert(v.contains("module BpStats("))
      assert(v.contains("input clock") && v.contains("input reset"))
      assert(v.contains("reg [31:0] stat_a /*verilator public_flat_rd*/;"))
      assert(v.contains("reg [7:0] stat_b /*verilator public_flat_rd*/;"))
      assert(v.contains("input stat_a_en") && v.contains("input [31:0] stat_a_data"))
      assert(v.contains("output [7:0] stat_b_value"))
      assert(v.contains("if (stat_a_en) stat_a <= stat_a_data;"))
      assert(v.contains("if (reset) begin"))
