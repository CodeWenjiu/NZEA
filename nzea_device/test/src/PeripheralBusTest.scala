package nzea_device

import nzea_device.devices.{PeripheralBus, PeripheralBusParameter}
import nzea_rtl.Elaborate
import utest.*

/** What the secondary bus does in hardware, measured: one access lasts `deviceCycles` device clocks, each of them
  * `clockHz / deviceHz` bus cycles, so the master waits `deviceCycles × step` cycles and no answer comes early; a store
  * applies its byte strobes and the next read sees them.
  *
  * The numbers are the ones the bridge flow's model resolves from the same two facts (`AccessCost.Periods`), which is
  * what makes the two flows comparable — a mismatch here means the model and the hardware drifted apart, and no
  * waveform on the board could tell which is wrong.
  *
  * The simulation is iverilog, from the dev shell.
  */
object PeripheralBusTest extends TestSuite:

    /** 100 MHz bus against a 25 MHz device clock: four bus cycles per device clock, two device clocks per access —
      * eight bus cycles. A ratio above one is deliberate; a 1:1 device clock would not show the division at all.
      */
    private val parameter =
        PeripheralBusParameter(
            addrWidth = 32,
            dataWidth = 32,
            userWidth = 4,
            idWidth = 1,
            clockHz = 100_000_000,
            deviceHz = 25_000_000,
            deviceCycles = 2
        )

    private val lowering = Seq(
        "--split-verilog",
        "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb"
    )

    private def writeConfig(cfg: os.Path): Unit =
        PeripheralBus.main(
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
                "--clockHz",
                parameter.clockHz.toString,
                "--deviceHz",
                parameter.deviceHz.toString,
                "--deviceCycles",
                parameter.deviceCycles.toString
            )
        )

    private def writeDesign(cfg: os.Path): Unit = PeripheralBus.main(Array("design", cfg.toString))

    val tests = Tests:
        test("an access lasts the device's own time, and a store applies its byte strobes"):
            val dir = os.temp.dir(prefix = "nzea-pbus")
            try
                Elaborate.withLinked(PeripheralBus.moduleName(parameter), dir, writeConfig, writeDesign): circuit =>
                    val rtl = dir / "rtl"
                    Elaborate.verilogInto(circuit, rtl, lowering)
                    os.write(dir / "tb.v", testbench)
                    val vvp = dir / "tb.vvp"
                    val sources = os.list(rtl).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                    os.proc("iverilog", "-g2012", "-o", vvp.toString, sources).call()
                    val out = os.proc("vvp", vvp.toString).call().out.text()
                    if !out.contains("PBUS: PASS") then println(out)
                    assert(out.contains("PBUS: PASS"))
            finally os.remove.all(dir)

    /** One master, one access at a time. `cycle` counts bus edges; the access's length is the distance between the edge
      * the request is taken on and the edge the answer is handed over.
      */
    private val testbench: String =
        """|`timescale 1ns/1ps
           |module tb;
           |  localparam STEP = 4;                 // clockHz / deviceHz
           |  localparam DEVICE_CYCLES = 2;
           |  localparam EXPECT = STEP * DEVICE_CYCLES;
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
           |
           |  PeripheralBus_d25000000x2 dut (
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
           |    .bus_resp_bits_id(resp_id)
           |  );
           |
           |  integer cycle = 0;
           |  integer fire_cycle = -1;
           |  integer resp_cycle = -1;
           |  integer errors = 0;
           |  integer length = 0;
           |  reg [31:0] last = '0;
           |
           |  always @(posedge clock) begin
           |    cycle = cycle + 1;
           |    if (resetN && req_valid && req_ready) fire_cycle = cycle;
           |    if (resetN && resp_valid && resp_ready) resp_cycle = cycle;
           |  end
           |
           |  task automatic access(input [31:0] a, input [31:0] d, input we, input [3:0] s);
           |    begin
           |      @(negedge clock);
           |      fire_cycle = -1;
           |      resp_cycle = -1;
           |      addr = a; wdata = d; wen = we; wstrb = s;
           |      req_valid = 1'b1; resp_ready = 1'b1;
           |      wait (fire_cycle >= 0);
           |      @(negedge clock);
           |      req_valid = 1'b0;
           |      wait (resp_cycle >= 0);
           |      last = resp_data;
           |      length = resp_cycle - fire_cycle;
           |      @(negedge clock);
           |      resp_ready = 1'b0;
           |    end
           |  endtask
           |
           |  task automatic expect_length(input [8*16-1:0] what);
           |    begin
           |      if (length != EXPECT) begin
           |        errors = errors + 1;
           |        $display("PBUS: %0s took %0d cycles, expected %0d", what, length, EXPECT);
           |      end
           |    end
           |  endtask
           |
           |  task automatic expect_data(input [8*16-1:0] what, input [31:0] want);
           |    begin
           |      if (last !== want) begin
           |        errors = errors + 1;
           |        $display("PBUS: %0s read %h, expected %h", what, last, want);
           |      end
           |    end
           |  endtask
           |
           |  initial begin
           |    repeat (10) @(posedge clock);
           |    resetN = 1'b1;
           |    repeat (2) @(posedge clock);
           |
           |    access(32'h0000_0010, 32'hDEAD_BEEF, 1'b1, 4'hF);
           |    expect_length("write");
           |    access(32'h0000_0010, 32'h0, 1'b0, 4'h0);
           |    expect_length("read");
           |    expect_data("word", 32'hDEAD_BEEF);
           |
           |    access(32'h0000_0014, 32'h1122_3344, 1'b1, 4'hF);
           |    access(32'h0000_0014, 32'h0000_AB00, 1'b1, 4'h2);
           |    access(32'h0000_0014, 32'h0, 1'b0, 4'h0);
           |    expect_data("byte strobe", 32'h1122_AB44);
           |
           |    // A second word is its own slot: a write to one must not disturb the other.
           |    access(32'h0000_0010, 32'h0, 1'b0, 4'h0);
           |    expect_data("untouched", 32'hDEAD_BEEF);
           |
           |    if (errors == 0) $display("PBUS: PASS (%0d cycles per access)", EXPECT);
           |    else $display("PBUS: FAIL (%0d errors)", errors);
           |    $finish;
           |  end
           |
           |  initial begin
           |    #200000;
           |    $display("PBUS: TIMEOUT");
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
