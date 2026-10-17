package nzea_rtl

import nzea_rtl.bus.{LiteBusCdc, LiteBusCdcParameter}
import utest.*

/** The crossing, two ways: what it elaborates to (each domain's flops in its own clock's block, the payload landing
  * register-to-register), and what it does under two clocks — a behavioral run with a fake slave on the far side whose
  * answer is derived from the request it saw, so a payload that crossed stale or shifted fails the near side's check.
  *
  * The simulation is iverilog, from the dev shell; a two-clock protocol is the one thing a structural test cannot see.
  */
object LiteBusCdcTest extends TestSuite:

    private val parameter =
        LiteBusCdcParameter(addrWidth = 32, dataWidth = 32, userWidth = 101, idWidth = 1, syncStages = 3)

    private val module = LiteBusCdc.moduleName(parameter)

    /** What the simulation drives: the same shape, two flops per flip. A *different* parameter from the structural
      * elaboration on purpose — the generator dumps a module once per parameter per JVM, so a second run of the same
      * one writes no file and the two tests would collide.
      */
    private val simParameter =
        LiteBusCdcParameter(addrWidth = 32, dataWidth = 32, userWidth = 101, idWidth = 1, syncStages = 2)

    private def writeConfig(p: LiteBusCdcParameter)(cfg: os.Path): Unit =
        LiteBusCdc.main(
            Array(
                "config",
                cfg.toString,
                "--addrWidth",
                p.addrWidth.toString,
                "--dataWidth",
                p.dataWidth.toString,
                "--userWidth",
                p.userWidth.toString,
                "--idWidth",
                p.idWidth.toString,
                "--syncStages",
                p.syncStages.toString
            )
        )

    private def writeDesign(cfg: os.Path): Unit = LiteBusCdc.main(Array("design", cfg.toString))

    private lazy val sv = TestFlow.lower(module, writeConfig(parameter), writeDesign)

    /** Every clocked block on `clock`, concatenated: firtool writes one `always` per register, so a domain's flops are
      * spread over as many blocks as it has state.
      */
    private def clocked(sv: String, clock: String): String =
        val marker = s"always @(posedge $clock"
        require(sv.contains(marker), s"no always block on $clock")
        val out = StringBuilder()
        var from = 0
        var more = true
        while more do
            val start = sv.indexOf(marker, from)
            if start < 0 then more = false
            else
                val next = sv.indexOf("always @", start + marker.length)
                val end = if next < 0 then sv.length else next
                out ++= sv.substring(start, end)
                from = end
        out.toString

    /** What the simulation lowers with: the dump's own flags (a split output, and the lowering options the dumps are
      * read with), so the SV under test is the SV the flows compile.
      */
    private val simLowering = Seq(
        "--split-verilog",
        "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays,noAlwaysComb"
    )

    val tests = Tests:

        test("the crossing's ports are the two buses and their two clocks"):
            assert(
                TestFlow.ports(sv, "LiteBusCdc") == Map(
                    "farClock" -> 1,
                    "farResetN" -> 1,
                    "far_req_bits_addr" -> 32,
                    "far_req_bits_id" -> 1,
                    "far_req_bits_user" -> 101,
                    "far_req_bits_wdata" -> 32,
                    "far_req_bits_wen" -> 1,
                    "far_req_bits_wstrb" -> 4,
                    "far_req_ready" -> 1,
                    "far_req_valid" -> 1,
                    "far_resp_bits_data" -> 32,
                    "far_resp_bits_id" -> 1,
                    "far_resp_bits_user" -> 101,
                    "far_resp_ready" -> 1,
                    "far_resp_valid" -> 1,
                    "nearClock" -> 1,
                    "nearResetN" -> 1,
                    "near_req_bits_addr" -> 32,
                    "near_req_bits_id" -> 1,
                    "near_req_bits_user" -> 101,
                    "near_req_bits_wdata" -> 32,
                    "near_req_bits_wen" -> 1,
                    "near_req_bits_wstrb" -> 4,
                    "near_req_ready" -> 1,
                    "near_req_valid" -> 1,
                    "near_resp_bits_data" -> 32,
                    "near_resp_bits_id" -> 1,
                    "near_resp_bits_user" -> 101,
                    "near_resp_ready" -> 1,
                    "near_resp_valid" -> 1
                )
            )

        test("each domain's flops are clocked by its own clock, and the payload is register-to-register"):
            val nearBlock = clocked(sv, "nearClock")
            val farBlock = clocked(sv, "farClock")
            // Held where they belong: the near side's payload and flip, the far side's capture and its own flip.
            val nearFlops = Seq("nearReqPayload", "nearReqToggle", "nearRespHeld", "nearRespValid")
            val farFlops = Seq("farReqHeld", "farReqSeen", "farRespHeld", "farRespToggle")
            assert(nearFlops.filterNot(nearBlock.contains) == Seq.empty)
            assert(farFlops.filterNot(farBlock.contains) == Seq.empty)
            // The crossing itself: a near-side register captured by a far-side one, and the reverse for the answer's
            // path — neither of them through a synchronizer, which is what the payload's stability allows.
            assert(farBlock.contains("nearReqPayload"))
            assert(nearBlock.contains("farRespHeld"))
            // ...and the flips do go through the synchronizer chains, first stage included.
            assert(farBlock.contains("nearReqToggle"))
            assert(nearBlock.contains("farRespToggle"))

        test("the crossing carries transactions, not bits: two clocks, a random-latency slave, and a late far release"):
            val dir = os.temp.dir(prefix = "nzea-cdc")
            try
                Elaborate.withLinked(LiteBusCdc.moduleName(simParameter), dir, writeConfig(simParameter), writeDesign):
                    circuit =>
                        val rtl = dir / "rtl"
                        Elaborate.verilogInto(circuit, rtl, simLowering)
                        os.write(dir / "tb.v", testbench)
                        val vvp = dir / "tb.vvp"
                        val sources = os.list(rtl).filter(_.ext == "sv").map(_.toString) :+ (dir / "tb.v").toString
                        os.proc("iverilog", "-g2012", "-o", vvp.toString, sources).call()
                        val out = os.proc("vvp", vvp.toString).call().out.text()
                        assert(out.contains("CDC: PASS"))
            finally os.remove.all(dir)

    /** A near-side master walking `N` transactions through the bridge, and a far-side slave answering each with the
      * request's own address (xored) and user payload echoed back. Both sides' resets assert together ten transactions
      * in — the far one released later, the asymmetry the crossing is stated to survive.
      */
    private val testbench: String =
        """|`timescale 1ns/1ps
           |module tb;
           |  localparam N = 40;
           |
           |  // 100 MHz against 160 MHz: a non-integer ratio, so the edges walk against each other over the run.
           |  reg nearClock = 1'b0;
           |  always #5.0 nearClock = ~nearClock;
           |  reg farClock = 1'b0;
           |  always #3.125 farClock = ~farClock;
           |
           |  reg nearResetN = 1'b0;
           |  reg farResetN = 1'b0;
           |
           |  reg [31:0] near_req_addr = '0;
           |  reg [31:0] near_req_wdata = '0;
           |  reg near_req_wen = 1'b0;
           |  reg [3:0] near_req_wstrb = '0;
           |  reg [100:0] near_req_user = '0;
           |  reg near_req_id = 1'b0;
           |  reg near_req_valid = 1'b0;
           |  wire near_req_ready;
           |  wire near_resp_valid;
           |  wire [31:0] near_resp_data;
           |  wire [100:0] near_resp_user;
           |  wire near_resp_id;
           |  reg near_resp_ready = 1'b0;
           |
           |  wire far_req_valid;
           |  wire [31:0] far_req_addr;
           |  wire [31:0] far_req_wdata;
           |  wire far_req_wen;
           |  wire [3:0] far_req_wstrb;
           |  wire [100:0] far_req_user;
           |  wire far_req_id;
           |  reg far_req_ready = 1'b0;
           |  reg far_resp_valid = 1'b0;
           |  reg [31:0] far_resp_data = '0;
           |  reg [100:0] far_resp_user = '0;
           |  reg far_resp_id = 1'b0;
           |  wire far_resp_ready;
           |
           |  LiteBusCdc dut (
           |    .nearClock(nearClock),
           |    .nearResetN(nearResetN),
           |    .near_req_valid(near_req_valid),
           |    .near_req_ready(near_req_ready),
           |    .near_req_bits_addr(near_req_addr),
           |    .near_req_bits_wdata(near_req_wdata),
           |    .near_req_bits_wen(near_req_wen),
           |    .near_req_bits_wstrb(near_req_wstrb),
           |    .near_req_bits_user(near_req_user),
           |    .near_req_bits_id(near_req_id),
           |    .near_resp_ready(near_resp_ready),
           |    .near_resp_valid(near_resp_valid),
           |    .near_resp_bits_data(near_resp_data),
           |    .near_resp_bits_user(near_resp_user),
           |    .near_resp_bits_id(near_resp_id),
           |    .farClock(farClock),
           |    .farResetN(farResetN),
           |    .far_req_valid(far_req_valid),
           |    .far_req_ready(far_req_ready),
           |    .far_req_bits_addr(far_req_addr),
           |    .far_req_bits_wdata(far_req_wdata),
           |    .far_req_bits_wen(far_req_wen),
           |    .far_req_bits_wstrb(far_req_wstrb),
           |    .far_req_bits_user(far_req_user),
           |    .far_req_bits_id(far_req_id),
           |    .far_resp_valid(far_resp_valid),
           |    .far_resp_ready(far_resp_ready),
           |    .far_resp_bits_data(far_resp_data),
           |    .far_resp_bits_user(far_resp_user),
           |    .far_resp_bits_id(far_resp_id)
           |  );
           |
           |  // The far slave: takes a request whenever it is idle, answers after a pseudo-random stall, and derives every
           |  // field of the answer from the request it saw — a payload that crossed stale or shifted is what the near side's
           |  // own check then sees.
           |  reg answering = 1'b0;
           |  reg [3:0] stall = 4'd0;
           |  reg [31:0] held_addr = '0;
           |  reg [100:0] held_user = '0;
           |  reg held_id = 1'b0;
           |  reg [15:0] lfsr = 16'hACE1;
           |
           |  always @(posedge farClock) lfsr <= {lfsr[14:0], lfsr[15] ^ lfsr[13] ^ lfsr[12] ^ lfsr[10]};
           |  always @(*) far_req_ready = farResetN & ~answering;
           |
           |  always @(posedge farClock) begin
           |    if (!farResetN) begin
           |      answering <= 1'b0;
           |      far_resp_valid <= 1'b0;
           |      stall <= 4'd0;
           |    end else begin
           |      far_resp_valid <= 1'b0;
           |      if (!answering && far_req_valid && far_req_ready) begin
           |        held_addr <= far_req_addr;
           |        held_user <= far_req_user;
           |        held_id <= far_req_id;
           |        stall <= {1'b0, lfsr[2:0]};
           |        answering <= 1'b1;
           |      end else if (answering) begin
           |        if (stall == 4'd0) begin
           |          far_resp_data <= held_addr ^ 32'hA5A5_5A5A;
           |          far_resp_user <= held_user;
           |          far_resp_id <= held_id;
           |          far_resp_valid <= 1'b1;
           |          answering <= 1'b0;
           |        end else begin
           |          stall <= stall - 4'd1;
           |        end
           |      end
           |    end
           |  end
           |
           |  // The near master: one transaction at a time, every answer checked against what was sent.
           |  reg [31:0] exp_data = '0;
           |  reg [100:0] exp_user = '0;
           |  reg exp_id = 1'b0;
           |  integer sent = 0;
           |  integer checked = 0;
           |  integer errors = 0;
           |  reg [1:0] state = 2'd0;
           |
           |  task drive(input integer i);
           |    begin
           |      exp_user = {37'h1_2345_6789, 32'h1000 + i[31:0], 32'h5A5A_0000 | i[31:0]};
           |      exp_data = (32'h8000_0000 + i * 4) ^ 32'hA5A5_5A5A;
           |      exp_id = i[0];
           |      near_req_addr = 32'h8000_0000 + i * 4;
           |      near_req_wdata = 32'hDEAD_0000 + i[31:0];
           |      near_req_wen = i[0];
           |      near_req_wstrb = i[0] ? 4'hF : 4'h0;
           |      near_req_user = exp_user;
           |      near_req_id = i[0];
           |    end
           |  endtask
           |
           |  always @(posedge nearClock) begin
           |    if (!nearResetN) begin
           |      state <= 2'd0;
           |      near_req_valid <= 1'b0;
           |      near_resp_ready <= 1'b0;
           |    end else begin
           |      near_resp_ready <= 1'b0;
           |      case (state)
           |        2'd0: if (checked < N) begin
           |                drive(checked);
           |                near_req_valid <= 1'b1;
           |                state <= 2'd1;
           |              end
           |        2'd1: if (near_req_valid && near_req_ready) begin
           |                sent <= sent + 1;
           |                near_req_valid <= 1'b0;
           |                state <= 2'd2;
           |              end
           |        2'd2: if (near_resp_valid) begin
           |                if (near_resp_data !== exp_data || near_resp_user !== exp_user || near_resp_id !== exp_id) begin
           |                  errors <= errors + 1;
           |                  $display("CDC: mismatch at %0d: data %h/%h user %h/%h id %b/%b",
           |                           checked, near_resp_data, exp_data, near_resp_user, exp_user, near_resp_id, exp_id);
           |                end
           |                near_resp_ready <= 1'b1;
           |                checked <= checked + 1;
           |                state <= 2'd0;
           |              end
           |      endcase
           |    end
           |  end
           |
           |  // Ten transactions in, both sides reset together; the near side is released first and the far side later, the
           |  // asymmetry the crossing is stated to survive (the campaign continues where it stood).
           |  reg resetInjected = 1'b0;
           |  initial begin
           |    wait (checked >= 10 && !resetInjected);
           |    resetInjected = 1'b1;
           |    nearResetN = 1'b0;
           |    farResetN = 1'b0;
           |    #123;
           |    nearResetN = 1'b1;
           |    #61;
           |    farResetN = 1'b1;
           |  end
           |
           |  initial begin
           |    #100;
           |    nearResetN = 1'b1;
           |    #37;
           |    farResetN = 1'b1;
           |    wait (checked >= N);
           |    #500;
           |    if (errors == 0) $display("CDC: PASS (%0d transactions, %0d far accesses)", checked, sent);
           |    else $display("CDC: FAIL (%0d checked, %0d sent, %0d errors)", checked, sent, errors);
           |    $finish;
           |  end
           |
           |  initial begin
           |    #200000;
           |    $display("CDC: TIMEOUT (%0d checked, %0d sent, %0d errors)", checked, sent, errors);
           |    $finish;
           |  end
           |endmodule
           |""".stripMargin
