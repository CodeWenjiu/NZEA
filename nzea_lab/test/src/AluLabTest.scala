package nzea_lab

import nzea_lab.generators.{AluGen, given}
import utest.*

/** The exported ALU, checked the way the course checks it: plain Verilog, no `-g2012`, a behavioural model as the
  * judge. The course's own reference is a stage-I netlist whose `011` is not SLTU, so the test states the spec itself
  * and uses the reference only where it is authoritative.
  *
  * The elaboration writes its intermediates into the process's working directory, so the port check and the simulation
  * share one elaboration rather than running two in parallel.
  */
object AluLabTest extends TestSuite:

    val tests = Tests:

        test("alu: parameter, ports, and all eight operations"):
            assert(AluGen.params.map(_.name) == Seq("dataWidth"))
            val dir = os.temp.dir(prefix = "nzea-lab-alu")
            try
                AluGen.generate(ParamValues(Map("dataWidth" -> "32")), dir)
                val ports = LabPorts.of(os.read(dir / "alu.sv"))
                assert(
                    ports.map(port => (port.name, port.dir, port.width)) == Seq(
                        ("A", PortDir.In, 32),
                        ("B", PortDir.In, 32),
                        ("ALUop", PortDir.In, 3),
                        ("Overflow", PortDir.Out, 1),
                        ("CarryOut", PortDir.Out, 1),
                        ("Zero", PortDir.Out, 1),
                        ("Result", PortDir.Out, 32)
                    )
                )

                os.write(dir / "tb.v", testbench)
                val sources = os.list(dir).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                val vvp = dir / "tb.vvp"
                os.proc("iverilog", "-o", vvp.toString, sources).call()
                val out = os.proc("vvp", vvp.toString).call().out.text()
                if !out.contains("ALU: PASS") then println(out)
                assert(out.contains("ALU: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ns
           |module alu_lab_test;
           |  reg [31:0] A, B;
           |  reg [2:0] op;
           |  wire [31:0] R;
           |  wire O, C, Z;
           |
           |  alu dut(.A(A), .B(B), .ALUop(op), .Overflow(O), .CarryOut(C), .Zero(Z), .Result(R));
           |
           |  function [31:0] value(input [2:0] o, input [31:0] a, input [31:0] b);
           |    case (o)
           |      3'b000: value = a & b;
           |      3'b001: value = a | b;
           |      3'b010: value = a + b;
           |      3'b011: value = (a < b) ? 32'd1 : 32'd0;
           |      3'b100: value = a ^ b;
           |      3'b101: value = ~(a | b);
           |      3'b110: value = a - b;
           |      3'b111: value = ($signed(a) < $signed(b)) ? 32'd1 : 32'd0;
           |      default: value = 32'hxxxxxxxx;
           |    endcase
           |  endfunction
           |
           |  function carry(input [2:0] o, input [31:0] a, input [31:0] b);
           |    reg [32:0] sum;
           |    begin
           |      sum = {1'b0, a} + {1'b0, b};
           |      case (o)
           |        3'b010: carry = sum[32];
           |        3'b110: carry = (a < b);
           |        default: carry = 1'b0;
           |      endcase
           |    end
           |  endfunction
           |
           |  function overflow(input [2:0] o, input [31:0] a, input [31:0] b, input [31:0] r);
           |    case (o)
           |      3'b010: overflow = (a[31] == b[31]) && (r[31] != a[31]);
           |      3'b110: overflow = (a[31] != b[31]) && (r[31] != a[31]);
           |      default: overflow = 1'b0;
           |    endcase
           |  endfunction
           |
           |  integer i, o;
           |  reg [31:0] expected;
           |  initial begin
           |    for (o = 0; o < 8; o = o + 1) begin
           |      op = o[2:0];
           |      for (i = 0; i < 5000; i = i + 1) begin
           |        A = $random;
           |        B = $random;
           |        #1;
           |        expected = value(op, A, B);
           |        if (R !== expected || Z !== (expected === 32'd0) ||
           |            C !== carry(op, A, B) || O !== overflow(op, A, B, expected)) begin
           |          $display("ERROR op=%b A=%h B=%h R=%h expected=%h Z=%b C=%b/%b O=%b/%b",
           |                   op, A, B, R, expected, Z, C, carry(op, A, B), O, overflow(op, A, B, expected));
           |          $finish;
           |        end
           |      end
           |    end
           |    $display("ALU: PASS");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
