package nzea_lab

import nzea_lab.generators.{ShifterGen, given}
import utest.*

/** The exported shifter, against a behavioural model for every operation and every shift amount. */
object ShifterLabTest extends TestSuite:

    val tests = Tests:

        test("shifter: three operations over every shift amount"):
            val dir = os.temp.dir(prefix = "nzea-lab-shifter")
            try
                ShifterGen.generate(ParamValues(Map("dataWidth" -> "32")), dir)
                os.write(dir / "tb.v", testbench)
                val sources = os.list(dir).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                val vvp = dir / "tb.vvp"
                os.proc("iverilog", "-o", vvp.toString, sources).call()
                val out = os.proc("vvp", vvp.toString).call().out.text()
                if !out.contains("SHIFTER: PASS") then println(out)
                assert(out.contains("SHIFTER: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ns
           |module shifter_lab_test;
           |  reg [31:0] A;
           |  reg [4:0] B;
           |  reg [1:0] op;
           |  wire [31:0] R;
           |
           |  shifter dut(.A(A), .B(B), .Shiftop(op), .Result(R));
           |
           |  integer i, j, k;
           |  reg [31:0] expected;
           |  initial begin
           |    for (j = 0; j < 4; j = j + 1) begin
           |      op = j[1:0];
           |      for (i = 0; i < 32; i = i + 1) begin
           |        B = i[4:0];
           |        for (k = 0; k < 200; k = k + 1) begin
           |          A = $random;
           |          #1;
           |          case (op)
           |            2'b00: expected = A << B;
           |            2'b01: expected = 32'd0;
           |            2'b10: expected = A >> B;
           |            2'b11: expected = $signed(A) >>> B;
           |          endcase
           |          if (R !== expected) begin
           |            $display("ERROR op=%b A=%h B=%0d R=%h expected=%h", op, A, B, R, expected);
           |            $finish;
           |          end
           |        end
           |      end
           |    end
           |    $display("SHIFTER: PASS");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
