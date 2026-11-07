package nzea_lab

import nzea_lab.generators.{RegFileGen, given}
import utest.*

/** The exported register file: write every register, then read random pairs against a shadow array. x0 is written once
  * and must stay zero. The elaboration writes intermediates into the process's working directory, so the suite keeps
  * one generation per test.
  */
object RegFileLabTest extends TestSuite:

    val tests = Tests:

        test("reg_file: 2R1W, x0 hardwired"):
            val dir = os.temp.dir(prefix = "nzea-lab-reg-file")
            try
                RegFileGen.generate(ParamValues(Map("dataWidth" -> "32", "addrWidth" -> "5")), dir)
                os.write(dir / "tb.v", testbench)
                val sources = os.list(dir).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                val vvp = dir / "tb.vvp"
                os.proc("iverilog", "-o", vvp.toString, sources).call()
                val out = os.proc("vvp", vvp.toString).call().out.text()
                if !out.contains("REG_FILE: PASS") then println(out)
                assert(out.contains("REG_FILE: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ns
           |module reg_file_lab_test;
           |  reg clk = 0;
           |  always #1 clk = ~clk;
           |
           |  reg [4:0] waddr, raddr1, raddr2;
           |  reg wen;
           |  reg [31:0] wdata;
           |  wire [31:0] rdata1, rdata2;
           |  reg [31:0] shadow [0:31];
           |
           |  reg_file dut(.clk(clk), .waddr(waddr), .raddr1(raddr1), .raddr2(raddr2),
           |               .wen(wen), .wdata(wdata), .rdata1(rdata1), .rdata2(rdata2));
           |
           |  integer i, a, b;
           |  initial begin
           |    waddr = 0; raddr1 = 0; raddr2 = 0; wen = 0; wdata = 0;
           |    for (i = 0; i < 32; i = i + 1) shadow[i] = 32'h0;
           |
           |    for (i = 1; i < 32; i = i + 1) begin
           |      @(negedge clk);
           |      waddr = i[4:0]; wdata = i * 32'h01010101; wen = 1'b1;
           |      @(posedge clk); #0.1;
           |      shadow[i] = wdata;
           |    end
           |    @(negedge clk); wen = 0;
           |
           |    @(negedge clk); waddr = 0; wdata = 32'hdeadbeef; wen = 1'b1;
           |    @(posedge clk); #0.1;
           |    @(negedge clk); wen = 0;
           |
           |    for (i = 0; i < 2000; i = i + 1) begin
           |      @(negedge clk);
           |      a = $random & 31;
           |      b = $random & 31;
           |      raddr1 = a[4:0];
           |      raddr2 = b[4:0];
           |      #0.1;
           |      if (rdata1 !== shadow[a] || rdata2 !== shadow[b]) begin
           |        $display("ERROR i=%0d a=%0d b=%0d r1=%h exp=%h r2=%h exp=%h",
           |                 i, a, b, rdata1, shadow[a], rdata2, shadow[b]);
           |        $finish;
           |      end
           |    end
           |    $display("REG_FILE: PASS");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
