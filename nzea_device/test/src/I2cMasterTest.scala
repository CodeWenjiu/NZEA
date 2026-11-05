package nzea_device

import nzea_device.devices.{I2cMaster, I2cMasterParameter}
import nzea_rtl.{Elaborate, RtlFlow}
import nzea_rtl.stat.StatScope
import utest.*

/** What an I2C access costs in hardware, measured: a write frame is 56 bit periods, a read frame 66, and the port is
  * held for the whole frame.
  *
  * The bit period is `clockHz / bitHz`; 1 MHz against 100 kbit/s makes it ten cycles, so the two frame lengths are easy
  * to see in a short simulation. The pointers and data bytes are not read back here — the wire's length is the fact the
  * model needs, and the far end is the simulator's in the bridge flow.
  *
  * The simulation is iverilog, from the dev shell.
  */
object I2cMasterTest extends TestSuite:

    private val parameter =
        I2cMasterParameter(
            addrWidth = 32,
            dataWidth = 32,
            userWidth = 4,
            idWidth = 1,
            base = 0L,
            clockHz = 1_000_000,
            bitHz = 100_000,
            slaveAddr = 0x10,
            counters = false,
            stats = StatScope.root,
            flow = RtlFlow.Yosys
        )

    private val lowering = Seq(
        "--split-verilog",
        "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb"
    )

    private def writeConfig(cfg: os.Path): Unit =
        I2cMaster.main(
            Array(
                "config",
                cfg.toString,
                "--addrWidth",
                parameter.addrWidth.toString,
                "--dataWidth",
                parameter.dataWidth.toString,
                "--userWidth",
                parameter.userWidth.toString,
                "--idWidth",
                parameter.idWidth.toString,
                "--base",
                parameter.base.toString,
                "--clockHz",
                parameter.clockHz.toString,
                "--bitHz",
                parameter.bitHz.toString,
                "--slaveAddr",
                parameter.slaveAddr.toString,
                "--counters",
                "false",
                "--stats",
                "root",
                "--flow",
                "yosys"
            )
        )

    private def writeDesign(cfg: os.Path): Unit = I2cMaster.main(Array("design", cfg.toString))

    private val moduleName = I2cMaster.moduleName(parameter)

    val tests = Tests:
        test("a write frame is 56 bit periods and a read frame is 66, and the port is held for the whole frame"):
            val dir = os.temp.dir(prefix = "nzea-i2c")
            try
                Elaborate.withLinked(I2cMaster.moduleName(parameter), dir, writeConfig, writeDesign): circuit =>
                    val rtl = dir / "rtl"
                    Elaborate.verilogInto(circuit, rtl, lowering)
                    os.write(dir / "tb.v", testbench)
                    val vvp = dir / "tb.vvp"
                    val sources = os.list(rtl).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                    os.proc("iverilog", "-g2012", "-o", vvp.toString, sources).call()
                    val out = os.proc("vvp", vvp.toString).call().out.text()
                    if !out.contains("I2C: PASS") then println(out)
                    assert(out.contains("I2C: PASS"))
            finally os.remove.all(dir)

    private val testbench: String =
        """|`timescale 1ns/1ps
           |module tb;
           |  localparam BIT = 10;                  // clockHz / bitHz
           |  localparam WRITE_BITS = 56;
           |  localparam READ_BITS = 66;
           |
           |  reg clock = 1'b0;
           |  always #5 clock = ~clock;
           |  reg resetN = 1'b0;
           |
           |  reg  [31:0] addr = '0;
           |  reg  [31:0] wdata = '0;
           |  reg         wen = 1'b0;
           |  reg  [3:0]  wstrb = '0;
           |  reg  [3:0]  user = '0;
           |  reg         id = 1'b0;
           |  reg         req_valid = 1'b0;
           |  wire        req_ready;
           |  reg         resp_ready = 1'b0;
           |  wire        resp_valid;
           |  wire [31:0] resp_data;
           |  wire [3:0]  resp_user;
           |  wire        resp_id;
           |  wire        scl, sda;
           |
           |  __MODULE__ dut (
           |    .clock(clock),
           |    .resetN(resetN),
           |    .bus_req_valid(req_valid),
           |    .bus_req_ready(req_ready),
           |    .bus_req_bits_addr(addr),
           |    .bus_req_bits_wdata(wdata),
           |    .bus_req_bits_wen(wen),
           |    .bus_req_bits_wstrb(wstrb),
           |    .bus_req_bits_user(user),
           |    .bus_req_bits_id(id),
           |    .bus_resp_valid(resp_valid),
           |    .bus_resp_ready(resp_ready),
           |    .bus_resp_bits_data(resp_data),
           |    .bus_resp_bits_user(resp_user),
           |    .bus_resp_bits_id(resp_id),
           |    .boundary_scl(scl),
           |    .boundary_sda(sda)
           |  );
           |
           |  integer cycle = 0;
           |  integer fire_cycle = -1;
           |  integer resp_cycle = -1;
           |  integer length = 0;
           |  integer errors = 0;
           |
           |  // The fire is sampled at the edge it happens on; the answer is a register, so it is sampled after the edge
           |  // that sets it — one cycle later than a posedge sample would see it.
           |  always @(posedge clock) begin
           |    cycle = cycle + 1;
           |    if (resetN && req_valid && req_ready) fire_cycle = cycle;
           |  end
           |
           |  always @(negedge clock) begin
           |    if (resetN && resp_valid && resp_ready && resp_cycle < 0) resp_cycle = cycle;
           |  end
           |
           |  task automatic check_held;
           |    begin
           |      if (req_ready) begin
           |        errors = errors + 1;
           |        $display("I2C: the port accepted a request in the first cycle of a frame");
           |      end
           |    end
           |  endtask
           |
           |  task automatic access(input [31:0] a, input [31:0] d, input we);
           |    begin
           |      @(negedge clock);
           |      fire_cycle = -1;
           |      resp_cycle = -1;
           |      addr = a; wdata = d; wen = we; wstrb = 4'hF;
           |      req_valid = 1'b1; resp_ready = 1'b1;
           |      wait (fire_cycle >= 0);
           |      @(negedge clock);
           |      check_held;
           |      req_valid = 1'b0;
           |      wait (resp_cycle >= 0);
           |      length = resp_cycle - fire_cycle;
           |      @(negedge clock);
           |      resp_ready = 1'b0;
           |    end
           |  endtask
           |
           |  task automatic expect_length(input [8*16-1:0] what, input integer want);
           |    begin
           |      if (length != want) begin
           |        errors = errors + 1;
           |        $display("I2C: %0s took %0d cycles, expected %0d", what, length, want);
           |      end
           |    end
           |  endtask
           |
           |  initial begin
           |    repeat (10) @(posedge clock);
           |    resetN = 1'b1;
           |    repeat (2) @(posedge clock);
           |
           |    access(32'h0000_0010, 32'hDEAD_BEEF, 1'b1);
           |    expect_length("write", WRITE_BITS * BIT);
           |    access(32'h0000_0010, 32'h0, 1'b0);
           |    expect_length("read", READ_BITS * BIT);
           |    access(32'h0000_0014, 32'h1122_3344, 1'b1);
           |    expect_length("second write", WRITE_BITS * BIT);
           |
           |    if (errors == 0) $display("I2C: PASS (%0d/%0d cycles)", WRITE_BITS * BIT, READ_BITS * BIT);
           |    else $display("I2C: FAIL (%0d errors)", errors);
           |    $finish;
           |  end
           |
           |  initial begin
           |    #200000;
           |    $display("I2C: TIMEOUT");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin.replace("__MODULE__", moduleName)
