// Simulation stand-in for the MIG example design's `example_top`.
//
// The parameter and port lists match `example_top` exactly, so the vendor's own
// `sim_tb_top.v` can be used unmodified apart from the module name. Everything
// the vendor TB owns - clocking, reset polarity, PCB propagation delays, the
// Micron `ddr3_model` instantiation, the pass/fail watchdog - stays vendor code.
//
// What is inside here is the production RTL: `Ddr3Subsystem` (which wraps the
// real generated MIG IP) plus `Ddr3Adapter` (our LiteBus bridge), driven by a
// small write/read/compare FSM. No behavioural model of the MIG appears in this
// file, which is the whole point: a protocol mistake in the adapter is seen by
// the real controller instead of by a stub written from the same guess.
//
// The tile-side clock is generated locally as an independent 100 MHz source. On
// hardware it comes from the board MMCM while `ui_clk` comes from the MIG's own
// PLL: same frequency, different source, so the phase relationship is arbitrary.
// Reproducing that is what lets this simulation see CDC problems.
//
// Simulation only - never instantiated in a board build.

`timescale 1ps/100fs

module nzea_ddr3_sim_top #
  (
   // Accepted to match example_top's parameter list; the traffic-generator
   // options have no meaning here and are deliberately unused.
   parameter SIMULATION            = "TRUE",
   parameter PORT_MODE             = "BI_MODE",
   parameter DATA_MODE             = 4'b0010,
   parameter TST_MEM_INSTR_MODE    = "R_W_INSTR_MODE",
   parameter EYE_TEST              = "FALSE",
   parameter DATA_PATTERN          = "DGEN_ALL",
   parameter CMD_PATTERN           = "CGEN_ALL",
   parameter BEGIN_ADDRESS         = 32'h00000000,
   parameter END_ADDRESS           = 32'h00ffffff,
   parameter PRBS_EADDR_MASK_POS   = 32'hff000000,
   parameter COL_WIDTH             = 10,
   parameter CS_WIDTH              = 1,
   parameter DM_WIDTH              = 2,
   parameter DQ_WIDTH              = 16,
   parameter DQS_CNT_WIDTH         = 1,
   parameter DRAM_WIDTH            = 8,
   parameter ECC_TEST              = "OFF",
   parameter RANKS                 = 1,
   parameter ROW_WIDTH             = 15,
   parameter ADDR_WIDTH            = 29,
   parameter BURST_MODE            = "8",
   parameter TCQ                   = 100,
   parameter DRAM_TYPE             = "DDR3",
   parameter nCK_PER_CLK           = 4,
   parameter DEBUG_PORT            = "OFF",
   parameter RST_ACT_LOW           = 1
   )
  (
   inout  [15:0] ddr3_dq,
   inout  [1:0]  ddr3_dqs_n,
   inout  [1:0]  ddr3_dqs_p,
   output [14:0] ddr3_addr,
   output [2:0]  ddr3_ba,
   output        ddr3_ras_n,
   output        ddr3_cas_n,
   output        ddr3_we_n,
   output        ddr3_reset_n,
   output [0:0]  ddr3_ck_p,
   output [0:0]  ddr3_ck_n,
   output [0:0]  ddr3_cke,
   output [1:0]  ddr3_dm,
   output [0:0]  ddr3_odt,
   input         sys_clk_i,
   output        tg_compare_error,
   output        init_calib_complete,
   input         sys_rst
   );

  // ---------------------------------------------------------------- clocks
  // 100 MHz, started at an offset that has no relationship to ui_clk.
  // PHASE plusarg shifts the tile clock vs the MIG's own ui_clk, sweeping the
  // one dimension that makes inter-clock races rare on the board: the pad
  // alignment between the two 100 MHz domains.
  reg clk_100m = 1'b0;
  integer ph_offset = 1237;
  initial begin
    if (!$value$plusargs("PHASE=%d", ph_offset)) ph_offset = 1237;
    #(ph_offset);
    forever #5000 clk_100m = ~clk_100m;
  end

  // --------------------------------------------------------------- resets
  // The vendor TB already applies RST_ACT_LOW to produce sys_rst, so with
  // RST_ACT_LOW=1 sys_rst is itself active-low.
  wire sys_rst_n = RST_ACT_LOW ? sys_rst : ~sys_rst;

  reg rst_100m = 1'b1;
  always @(posedge clk_100m) rst_100m <= ~sys_rst_n;

  // ------------------------------------------------- MIG side plumbing
  wire [28:0]  mig_app_addr;
  wire [2:0]   mig_app_cmd;
  wire         mig_app_en;
  wire         mig_app_rdy;
  wire [127:0] mig_wdf_data;
  wire         mig_wdf_wren;
  wire         mig_wdf_end;
  wire         mig_wdf_rdy;
  wire [127:0] mig_rd_data;
  wire         mig_rd_valid;
  wire         mig_rd_end;
  wire         calib_done;
  wire         mig_ui_clk;
  wire         mig_ui_rst;

  wire [14:0] pin_addr;
  wire [2:0]  pin_ba;
  wire        pin_ras_n, pin_cas_n, pin_we_n, pin_reset_n;
  wire        pin_ck_p, pin_ck_n, pin_cke, pin_odt;
  wire [1:0]  pin_dm;

  // The MIG's app_rd_data_end marks the LAST beat of a read-data burst. The
  // cosim MIG model delivers the 128-bit word in ONE beat (end is never
  // asserted), whereas the real board PHY may split it over two UI beats (end
  // on the second). The adapter samples on valid&&end. For the cosim model a
  // single beat means the only beat IS the last, so tie end = valid here;
  // on the board the real MIG drives end correctly.
  assign mig_rd_end = mig_rd_valid;

  // The generated Ddr3Subsystem is pure wiring around the MIG blackbox, so
  // firtool removed its clock/reset ports; do not connect any. This is an
  // ASSERTED assumption: vivado-sim.nu greps Ddr3Subsystem.sv for always
  // blocks and aborts if it ever gains sequential logic (which would break
  // this no-clock instantiation silently).
  Ddr3Subsystem u_ddr3_sub (
    .io_rst_n                 (sys_rst_n),
    .io_clk_200m              (sys_clk_i),
    .io_mig_app_addr          (mig_app_addr),
    .io_mig_app_cmd           (mig_app_cmd),
    .io_mig_app_en            (mig_app_en),
    .io_mig_app_rdy           (mig_app_rdy),
    .io_mig_app_wdf_data      (mig_wdf_data),
    .io_mig_app_wdf_wren      (mig_wdf_wren),
    .io_mig_app_wdf_end       (mig_wdf_end),
    .io_mig_app_wdf_rdy       (mig_wdf_rdy),
    .io_mig_app_rd_data       (mig_rd_data),
    .io_mig_app_rd_data_end   (mig_rd_end),
    .io_mig_app_rd_data_valid (mig_rd_valid),
    .io_mig_calib_done        (calib_done),
    .io_mig_ui_clk            (mig_ui_clk),
    .io_mig_ui_rst            (mig_ui_rst),
    .io_ddr3_addr             (pin_addr),
    .io_ddr3_ba               (pin_ba),
    .io_ddr3_ras_n            (pin_ras_n),
    .io_ddr3_cas_n            (pin_cas_n),
    .io_ddr3_we_n             (pin_we_n),
    .io_ddr3_ck_p             (pin_ck_p),
    .io_ddr3_ck_n             (pin_ck_n),
    .io_ddr3_cke              (pin_cke),
    .io_ddr3_odt              (pin_odt),
    .io_ddr3_reset_n          (pin_reset_n),
    .io_ddr3_dm               (pin_dm),
    .dq                       (ddr3_dq),
    .dqs_p                    (ddr3_dqs_p),
    .dqs_n                    (ddr3_dqs_n)
  );

  assign ddr3_addr    = pin_addr;
  assign ddr3_ba      = pin_ba;
  assign ddr3_ras_n   = pin_ras_n;
  assign ddr3_cas_n   = pin_cas_n;
  assign ddr3_we_n    = pin_we_n;
  assign ddr3_reset_n = pin_reset_n;
  assign ddr3_ck_p    = pin_ck_p;
  assign ddr3_ck_n    = pin_ck_n;
  assign ddr3_cke     = pin_cke;
  assign ddr3_odt     = pin_odt;
  assign ddr3_dm      = pin_dm;

  assign init_calib_complete = calib_done;

  // ------------------------------------------------------------ test FSM
  // Write each pattern, read it back after a delay, compare. Mirrors the intent
  // of the (orphaned) Ddr3TestCore.
  localparam [2:0] S_IDLE  = 3'd0,
                   S_WR    = 3'd1,
                   S_WRACK = 3'd2,
                   S_DELAY = 3'd3,
                   S_RD    = 3'd4,
                   S_RDACK = 3'd5,
                   S_NEXT  = 3'd6,
                   S_DONE  = 3'd7;

  reg [2:0]  state    = S_IDLE;
  reg [2:0]  idx      = 3'd0;
  reg [4:0]  passbits = 5'b0;
  reg        done     = 1'b0;
  reg [5:0]  delayCnt = 6'd0;
  reg [31:0] got      = 32'h0;
  // Inter-transaction gap in clk_100m cycles, driven by the GAP plusarg. The
  // board's partial-write sequences (memtest phase 3) issue back-to-back
  // stores with no gap, while the earlier fixed delay of 16 masked the
  // back-to-back RMW behaviour of the real MIG.
  reg [5:0]  gap_cycles = 6'd15;
  initial begin
    if (!$value$plusargs("GAP=%d", gap_cycles)) gap_cycles = 6'd15;
  end

  // Declared here (the adapter instance below drives .io_bus_resp_flush from
  // it); the generation always-block sits after the adapter instance because it
  // also samples resp_valid.
  reg        storm_fired = 1'b0;
  reg        storm       = 1'b0;
  reg [1:0]  storm_cnt   = 2'd0;

  // Five distinct patterns at five distinct words. Repeating one address would
  // not expose an address-mapping mistake, and repeating one byte value would
  // not expose a byte-lane or mask mistake.
  wire [31:0] cur_addr = (idx == 3'd0) ? 32'h8800_0000 :
                         (idx == 3'd1) ? 32'h8800_0004 :
                         (idx == 3'd2) ? 32'h8800_0010 :
                         (idx == 3'd3) ? 32'h8800_0100 :
                                         32'h8800_1000 ;
  wire [31:0] cur_data = (idx == 3'd0) ? 32'hcafe_babe :
                         (idx == 3'd1) ? 32'hdead_beef :
                         (idx == 3'd2) ? 32'h1234_5678 :
                         (idx == 3'd3) ? 32'ha5a5_a5a5 :
                                         32'hffff_ffff ;

  // ------------------------------------------------------- bus stimulus
  // Combinational from the state, which is what the Chisel Ddr3TestCore does.
  //
  // Registering req_valid instead introduces a same-cycle override hazard:
  // `req_valid <= 1` followed by `if (req_ready) req_valid <= 0` inside one
  // always block means the second assignment wins, so on any entry cycle where
  // the adapter already happens to be ready the request is never presented and
  // the FSM still advances to a wait state it can never leave. That bug is
  // silent - the adapter looks idle and healthy while the test hangs.
  wire        req_valid  = (state == S_WR) || (state == S_RD);
  wire        req_wen    = (state == S_WR);
  wire        resp_ready = (state == S_WRACK) || (state == S_RDACK);
  wire        req_ready;
  wire        resp_valid;
  wire [31:0] resp_data;

  Ddr3Adapter u_adapter (
    .clock                    (clk_100m),
    .reset                    (rst_100m),
    .io_bus_req_ready         (req_ready),
    .io_bus_req_valid         (req_valid),
    .io_bus_req_bits_addr     (cur_addr),
    .io_bus_req_bits_wdata    (cur_data),
    .io_bus_req_bits_wen      (req_wen),
    .io_bus_req_bits_wstrb    (4'hf),
    .io_bus_req_bits_user     (68'h0),
    .io_bus_req_bits_id       (8'h0),
    .io_bus_resp_ready        (resp_ready),
    .io_bus_resp_valid        (resp_valid),
    .io_bus_resp_bits_data    (resp_data),
    .io_bus_resp_bits_user    (),
    .io_bus_resp_bits_id      (),
    .io_bus_resp_flush        (storm),
    .io_tileReset             (storm),
    .io_mig_app_addr          (mig_app_addr),
    .io_mig_app_cmd           (mig_app_cmd),
    .io_mig_app_en            (mig_app_en),
    .io_mig_app_rdy           (mig_app_rdy),
    .io_mig_app_wdf_data      (mig_wdf_data),
    .io_mig_app_wdf_wren      (mig_wdf_wren),
    .io_mig_app_wdf_end       (mig_wdf_end),
    .io_mig_app_wdf_rdy       (mig_wdf_rdy),
    .io_mig_app_rd_data       (mig_rd_data),
    .io_mig_app_rd_data_end   (mig_rd_end),
    .io_mig_app_rd_data_valid (mig_rd_valid),
    .io_mig_calib_done        (calib_done),
    .io_mig_ui_clk            (mig_ui_clk),
    .io_mig_ui_rst            (mig_ui_rst)
  );

  // Tile-reset storm: models an uart-load landing while the ui FSM is mid-RMW.
  // On the board this resets the tile (and so this FSM / the crossbar flush)
  // but NOT the board-level adapter nor its ui domain, which keeps running the
  // stale transaction and later emits a completion for a request that was
  // flushed - the "stale done" corruption from the bring-up. Power-on reset
  // (rst_100m) is deliberately NOT involved.
  // STORM_DELAY lands inside the first RMW's read wait (seen in the waveform:
  // read accepted ~1 cycle after the fire, data back ~50 cycles later).
  localparam [7:0] STORM_DELAY = 8'd30;
  reg [7:0] fire_timer = 8'd0;
  always @(posedge clk_100m) begin
    if (storm) begin
      storm_cnt <= storm_cnt - 2'd1;
      if (storm_cnt == 2'd1) storm <= 1'b0;
    end
    if (!storm_fired) begin
      if (fire_timer == 8'd0) begin
        // First request fire arms the timer; later fires are ignored.
        if (req_valid && req_ready) fire_timer <= 8'd1;
      end else if (fire_timer == STORM_DELAY) begin
        storm_fired <= 1'b1;
        storm       <= 1'b1;
        storm_cnt   <= 2'd2;
        $display("NZEA_DDR3_SIM: tile-reset storm fired");
      end else begin
        fire_timer <= fire_timer + 8'd1;
      end
    end
  end

  always @(posedge clk_100m) begin
    if (rst_100m || storm) begin
      state    <= S_IDLE;
      idx      <= 3'd0;
      passbits <= 5'b0;
      done     <= 1'b0;
      delayCnt <= 6'd0;
      got      <= 32'h0;
    end else begin
      case (state)
        S_IDLE: begin
          // The adapter refuses requests until calibration is synced into the
          // tile domain, so waiting here is what the real boot path does too.
          if (calib_done) state <= S_WR;
        end
        S_WR: begin
          if (req_ready) state <= S_WRACK;
        end
        S_WRACK: begin
          if (resp_valid) begin
            delayCnt <= 6'd0;
            state    <= S_DELAY;
          end
        end
        // A gap between the write retiring and the read starting. An immediate
        // read-back can succeed while the write never reached the DRAM, so a
        // delay is what separates "the adapter handed it over" from "it landed".
        S_DELAY: begin
          delayCnt <= delayCnt + 6'd1;
          if (delayCnt == gap_cycles) state <= S_RD;
        end
        S_RD: begin
          if (req_ready) state <= S_RDACK;
        end
        S_RDACK: begin
          if (resp_valid) begin
            got   <= resp_data;
            state <= S_NEXT;
          end
        end
        S_NEXT: begin
          if (got === cur_data) begin
            passbits <= passbits | (5'b1 << idx);
          end else begin
            $display("NZEA_DDR3_SIM: MISMATCH idx=%0d addr=%08x got=%08x exp=%08x",
                     idx, cur_addr, got, cur_data);
          end
          if (idx == 3'd4) begin
            done  <= 1'b1;
            state <= S_DONE;
          end else begin
            idx   <= idx + 3'd1;
            state <= S_WR;
          end
        end
        S_DONE: done <= 1'b1;
        default: state <= S_IDLE;
      endcase
    end
  end

  wire all_pass = (passbits == 5'b11111);
  // Polarity matches the vendor TB: 1 means error. Deliberately NOT gated on
  // `done` - the vendor TB checks this flag a fixed 50 us after calibration and
  // then calls $finish, so a test that never completes would otherwise be
  // reported as "TEST PASSED" simply because the error flag was still 0.
  assign tg_compare_error = !all_pass;

  // ------------------------------------------------------------- verdict
  // C5: the old `wait (done)` was level-sampled and raced the watchdog below —
  // if `done` never asserted, the watchdog's $finish could win and the verdict
  // block never printed PASS/FAIL (a failed test could look like a hang).
  // Sample `done` on clk_100m edges instead; the diagnostic watchdog below
  // owns the timeout path (it $finishes after 40 us if `done` never arrives),
  // so the two blocks cannot race on who reports.
  initial begin
    forever begin
      @(posedge clk_100m);
      if (done) begin
        #1000;
        if (all_pass)
          $display("NZEA_DDR3_SIM: PASS 5/5 write-read-compare against the real MIG");
        else
          $display("NZEA_DDR3_SIM: FAIL passbits=%05b", passbits);
        $finish;
      end
    end
  end

  // Diagnostic watchdog. Fires 10 us before the vendor TB's own post-calibration
  // check so a hang is reported with state, not just as a data error.
  initial begin
    wait (calib_done);
    #40_000_000; // 40 us
    if (!done) begin
      $display("NZEA_DDR3_SIM: TIMEOUT idx=%0d state=%0d passbits=%05b calib=%b ui_rst=%b app_rdy=%b rd_valid=%b",
               idx, state, passbits, calib_done, mig_ui_rst, mig_app_rdy, mig_rd_valid);
      $finish;
    end
  end

  // Waveform dump is opt-in (-testplusarg dump): the adapter's internals are
  // what matters, so the MIG and memory model are left out to keep the VCD small.
  initial begin
    if ($test$plusargs("dump")) begin
      $dumpfile("ddr3_cosim.vcd");
      $dumpvars(1, nzea_ddr3_sim_top);
      $dumpvars(0, nzea_ddr3_sim_top.u_adapter);
    end
  end

endmodule
