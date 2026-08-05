package nzea_rtl

import circt.stage.ChiselStage
import chisel3._
import org.scalatest.freespec.AnyFreeSpec

/** StatsRegs black-box tests: inline Verilog generation and port naming. */
/** Minimal harness to elaborate a [[StatsRegs]] black box. */
class StatsRegsHarness(moduleName: String, regs: Seq[(String, Int)]) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val stats = Module(new StatsRegs(moduleName, regs))
  stats.clock := clock
  stats.reset := reset
  stats.ports.values.foreach { p =>
    p.en := false.B
    p.data := 0.U
  }
}

class StatsRegsTest extends AnyFreeSpec {

  "verilog generation" in {
    val v = StatsRegs.verilog("BpStats", Seq("stat_a" -> 32, "stat_b" -> 8))
    assert(v.contains("module BpStats("), s"module header missing:\n$v")
    assert(v.contains("input clock") && v.contains("input reset"), s"clock/reset missing:\n$v")
    // Each register: annotated declaration, read-back assign, and write ports.
    assert(v.contains("reg [31:0] stat_a /*verilator public_flat_rd*/;"), s"stat_a decl:\n$v")
    assert(v.contains("reg [7:0] stat_b /*verilator public_flat_rd*/;"), s"stat_b decl:\n$v")
    assert(v.contains("input stat_a_en") && v.contains("input [31:0] stat_a_data"), s"stat_a ports:\n$v")
    assert(v.contains("output [7:0] stat_b_value"), s"stat_b value port:\n$v")
    assert(v.contains("if (stat_a_en) stat_a <= stat_a_data;"), s"stat_a write:\n$v")
    assert(v.contains("if (reset) begin"), s"reset handling:\n$v")
  }

  "elaborates with expected port names" in {
    val sv = ChiselStage.emitSystemVerilog(new StatsRegsHarness("BpStats", Seq("stat_a" -> 32, "stat_b" -> 8)))
    assert(sv.contains("module BpStats("), s"module header:\n$sv")
    assert(sv.contains("input stat_a_en"), s"stat_a_en port:\n$sv")
    assert(sv.contains("input [31:0] stat_a_data"), s"stat_a_data port:\n$sv")
    assert(sv.contains("output [31:0] stat_a_value"), s"stat_a_value port:\n$sv")
    assert(sv.contains("input [7:0] stat_b_data"), s"stat_b_data port:\n$sv")
    assert(sv.contains("output [7:0] stat_b_value"), s"stat_b_value port:\n$sv")
  }

}
