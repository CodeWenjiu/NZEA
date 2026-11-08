package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.bank.ReplacementState
import nzea_cache.config.Replacement
import nzea_rtl.{Elaborate, Lowering, RtlFlow}
import nzea_rtl.stdlib.log2Ceil
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
import utest.*

case class ReplacementTestParameter(sets: Int, ways: Int, policy: Replacement) extends Parameter

given upickle.default.ReadWriter[ReplacementTestParameter] = upickle.default.macroRW

class ReplacementTestLayers(parameter: ReplacementTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ReplacementTestIO(parameter: ReplacementTestParameter) extends HWBundle(parameter):
    val clk = Flipped(Clock())
    val resetN = Flipped(Reset())
    val set = Flipped(UInt(log2Ceil(parameter.sets)))
    val touchWay = Flipped(UInt(log2Ceil(parameter.ways)))
    val touchEn = Flipped(Bool())
    val victim = Aligned(UInt(log2Ceil(parameter.ways)))

class ReplacementTestProbe(parameter: ReplacementTestParameter)
    extends DVBundle[ReplacementTestParameter, ReplacementTestLayers](parameter)

@generator
object ReplacementTestGen
    extends Generator[ReplacementTestParameter, ReplacementTestLayers, ReplacementTestIO, ReplacementTestProbe]:

    override def moduleName(parameter: ReplacementTestParameter): String = "ReplacementTestModule"

    def architecture(parameter: ReplacementTestParameter) =
        val io = summon[Interface[ReplacementTestIO]]
        given ClockScope = ClockScope.posedge(io.clk)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val replacement = new ReplacementState(parameter.sets, parameter.ways, parameter.policy)
        when(io.touchEn) { replacement.touch(io.set, io.touchWay) }
        io.victim := replacement.way(io.set)

/** NMRU's two guarantees on a four-way set: the victim is never the way just touched, and the other three ways all stay
  * reachable.
  */
object ReplacementTest extends TestSuite:

    private val parameter = ReplacementTestParameter(sets = 2, ways = 4, policy = Replacement.Nmru)

    private def writeConfig(p: ReplacementTestParameter)(cfg: os.Path): Unit =
        ReplacementTestGen.main(
            Array(
                "config",
                cfg.toString,
                "--sets",
                p.sets.toString,
                "--ways",
                p.ways.toString,
                "--policy",
                p.policy.name
            )
        )

    private def writeDesign(cfg: os.Path): Unit = ReplacementTestGen.main(Array("design", cfg.toString))

    val tests = Tests:

        test("nmru: the victim skips the MRU way and still reaches the others"):
            val dir = os.temp.dir(prefix = "nzea-nmru")
            try
                Elaborate.withLinked(
                    ReplacementTestGen.moduleName(parameter),
                    dir,
                    writeConfig(parameter),
                    writeDesign
                ): circuit =>
                    val rtl = dir / "rtl"
                    Elaborate.verilogInto(circuit, rtl, Lowering.of(RtlFlow.Yosys))
                    os.write(dir / "tb.v", testbench)
                    val vvp = dir / "tb.vvp"
                    val sources = os.list(rtl).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                    os.proc("iverilog", "-g2012", "-o", vvp.toString, sources).call()
                    val out = os.proc("vvp", vvp.toString).call().out.text()
                    if !out.contains("NMRU: PASS") then println(out)
                    assert(out.contains("NMRU: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ps
           |module tb;
           |  reg clk = 1'b0;
           |  always #1 clk = ~clk;
           |
           |  reg resetN = 1'b0;
           |  reg set = 1'b0;
           |  reg [1:0] touchWay = 2'd0;
           |  reg touchEn = 1'b0;
           |  wire [1:0] victim;
           |
           |  ReplacementTestModule dut(
           |    .clk(clk), .resetN(resetN), .set(set),
           |    .touchWay(touchWay), .touchEn(touchEn), .victim(victim)
           |  );
           |
           |  reg [3:0] seen = 4'd0;
           |  integer errors = 0;
           |
           |  always @(negedge clk)
           |    if (resetN && touchEn) begin
           |      if (victim == touchWay) begin
           |        errors = errors + 1;
           |        $display("NMRU: victim %0d is the MRU way", victim);
           |      end
           |      seen[victim] = 1'b1;
           |    end
           |
           |  task start_phase(input [1:0] way);
           |    begin
           |      @(negedge clk); #0.1;
           |      seen = 4'd0;
           |      touchWay = way;
           |      touchEn = 1'b1;
           |    end
           |  endtask
           |
           |  initial begin
           |    #6 resetN = 1'b1;
           |
           |    start_phase(2'd2);
           |    repeat (40) @(posedge clk);
           |    #0.5;
           |    if (seen !== 4'b1011) begin
           |      errors = errors + 1;
           |      $display("NMRU: ways reached with MRU=2: %b", seen);
           |    end
           |
           |    start_phase(2'd3);
           |    repeat (40) @(posedge clk);
           |    #0.5;
           |    if (seen !== 4'b0111) begin
           |      errors = errors + 1;
           |      $display("NMRU: ways reached with MRU=3: %b", seen);
           |    end
           |
           |    if (errors == 0) $display("NMRU: PASS");
           |    else $display("NMRU: FAIL (%0d errors)", errors);
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
