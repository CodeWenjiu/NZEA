package nzea_lab

import nzea_lab.generators.{AluGen, RegFileGen, ShifterGen, SimpleCpuGen, given}
import utest.*

/** The single-cycle CPU end to end: the port contract, the black-box split, and a five-instruction program that
  * exercises the datapath, the register write-back and a store. The benchmark suite itself lives in the course tree;
  * this is the part the repository can check without it.
  *
  * One elaboration per parameter: zaozi remembers the modules a generator already emitted, so the port check and the
  * program share the same `simple_cpu` dump.
  */
object SimpleCpuLabTest extends TestSuite:

    val tests = Tests:

        test("simple_cpu: ports, black boxes, and a five-instruction program"):
            val dir = os.temp.dir(prefix = "nzea-lab-cpu")
            try
                SimpleCpuGen.generate(ParamValues(Map("dataWidth" -> "32")), dir)
                val ports = LabPorts.of(os.read(dir / "simple_cpu.sv"))
                assert(
                    ports.map(port => (port.name, port.dir, port.width)) == Seq(
                        ("clk", PortDir.In, 1),
                        ("rst", PortDir.In, 1),
                        ("PC", PortDir.Out, 32),
                        ("Instruction", PortDir.In, 32),
                        ("Address", PortDir.Out, 32),
                        ("MemWrite", PortDir.Out, 1),
                        ("Write_data", PortDir.Out, 32),
                        ("Write_strb", PortDir.Out, 4),
                        ("Read_data", PortDir.In, 32),
                        ("MemRead", PortDir.Out, 1)
                    )
                )
                assert(SimpleCpuGen.submodules == Seq("alu", "reg_file", "shifter"))
                val listed = os.read(dir / "filelist.f")
                assert(!listed.contains("alu.sv") && !listed.contains("reg_file.sv") && !listed.contains("shifter.sv"))

                AluGen.generate(ParamValues(Map("dataWidth" -> "32")), dir)
                RegFileGen.generate(ParamValues(Map("dataWidth" -> "32", "addrWidth" -> "5")), dir)
                ShifterGen.generate(ParamValues(Map("dataWidth" -> "32")), dir)

                os.write(dir / "tb.v", testbench)
                val sources = os.list(dir).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                val vvp = dir / "tb.vvp"
                os.proc("iverilog", "-o", vvp.toString, sources).call()
                val out = os.proc("vvp", vvp.toString).call().out.text()
                if !out.contains("CPU: PASS") then println(out)
                assert(out.contains("CPU: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ps
           |module cpu_lab_test;
           |  reg clk = 0;
           |  reg rst = 1;
           |  always #1 clk = ~clk;
           |
           |  reg [31:0] imem [0:1023];
           |  reg [31:0] dmem [0:1023];
           |
           |  wire [31:0] PC;
           |  wire [31:0] Instruction;
           |  wire [31:0] Address;
           |  wire        MemWrite;
           |  wire [31:0] Write_data;
           |  wire [3:0]  Write_strb;
           |  wire [31:0] Read_data;
           |  wire        MemRead;
           |
           |  assign Instruction = imem[PC[11:2]];
           |  assign Read_data = dmem[Address[11:2]];
           |
           |  simple_cpu dut(
           |    .clk(clk), .rst(rst), .PC(PC), .Instruction(Instruction),
           |    .Address(Address), .MemWrite(MemWrite), .Write_data(Write_data),
           |    .Write_strb(Write_strb), .Read_data(Read_data), .MemRead(MemRead)
           |  );
           |
           |  always @(posedge clk)
           |    if (MemWrite) dmem[Address[11:2]] <= Write_data;
           |
           |  integer i;
           |  initial begin
           |    for (i = 0; i < 1024; i = i + 1) begin
           |      imem[i] = 32'h00000013;
           |      dmem[i] = 32'h0;
           |    end
           |    imem[0] = 32'h00500093;
           |    imem[1] = 32'h00700113;
           |    imem[2] = 32'h002081b3;
           |    imem[3] = 32'h00300623;
           |    imem[4] = 32'h0000006f;
           |
           |    #3 rst = 0;
           |    #30;
           |    if (dmem[3] !== 32'd12) begin
           |      $display("ERROR dmem[3]=%h", dmem[3]);
           |      $finish;
           |    end
           |    $display("CPU: PASS");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
