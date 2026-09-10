// Partial-write RMW cosim: exercises the Ddr3Adapter's read-modify-write with
// NON-full wstrb (sb/sh), which the default wrapper never does (it always uses
// wstrb=0xF). Board bring-up showed intermittent corruption specifically on
// partial writes (row 5, half[12:13] reading 0), so this targets that exact
// path.
//
// Same parameter/port contract as example_top (required to reuse the vendor
// sim_tb_top.v). Run with: just vivado-sim --tb nzea_ddr3_pwrite_sim_top

`timescale 1ps/100fs

module nzea_ddr3_pwrite_sim_top #
  (
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

  reg clk_100m = 1'b0;
  integer ph_offset = 1237;
  initial begin
    if (!$value$plusargs("PHASE=%d", ph_offset)) ph_offset = 1237;
    #(ph_offset);
    forever #5000 clk_100m = ~clk_100m;
  end

  wire sys_rst_n = RST_ACT_LOW ? sys_rst : ~sys_rst;
  reg rst_100m = 1'b1;
  always @(posedge clk_100m) rst_100m <= ~sys_rst_n;

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

  // cosim MIG model: single-beat read response (end never asserted on the
  // real IP in simulation), so tie end = valid; the board PHY drives end.
  assign mig_rd_end = mig_rd_valid;

  wire [14:0] pin_addr;
  wire [2:0]  pin_ba;
  wire        pin_ras_n, pin_cas_n, pin_we_n, pin_reset_n;
  wire        pin_ck_p, pin_ck_n, pin_cke, pin_odt;
  wire [1:0]  pin_dm;

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

  // ──────────────────────────────── test FSM
  // Scenario (mirrors memtest phase 3+4 / pwrite_scan):
  //   1. full-word write row 0 (offset 0..15) = 0xDEADBEEF ^ word
  //   2. partial-write: sh 0xF00D @ offset 12  (wstrb=0b0011)
  //   3. read back word @ offset 12 -> expect 0xDEADF00D
  //      (low half strobed to F00D, high half DEAD preserved from step 1)
  // This exercises mergedFrom with wstrb=0b0011 at slotIdx=1, halfSel=1.

  localparam [3:0] S_IDLE=0, S_FULL_WR=1, S_FULL_WRACK=2,
                   S_PW=3, S_PWACK=4, S_RD=5, S_RDACK=6, S_DONE=7;

  reg [3:0]  state    = S_IDLE;
  reg [31:0] got      = 32'h0;
  reg        done     = 1'b0;
  reg        pass     = 1'b0;
  reg [3:0]  word_idx = 4'd0;

  // Row 0 base = 0x88000000
  wire [31:0] row_base = 32'h8800_0000;
  wire [31:0] cur_addr = row_base + {28'h0, word_idx[1:0], 2'b00};

  // Full-word write data: 0xDEADBEEF ^ word_idx
  wire [31:0] full_wdata = 32'hDEADBEEF ^ {28'h0, word_idx};

  wire        req_valid  = (state == S_FULL_WR) || (state == S_PW) || (state == S_RD);
  wire        req_wen    = (state == S_FULL_WR) || (state == S_PW);
  wire [31:0] req_addr   = (state == S_FULL_WR) ? cur_addr : (row_base + 32'hC);
  wire [31:0] req_wdata  = (state == S_FULL_WR) ? full_wdata : 32'h0000_F00D;
  wire [3:0]  req_wstrb  = (state == S_FULL_WR) ? 4'hF : 4'b0011;
  wire        resp_ready = (state == S_FULL_WRACK) || (state == S_PWACK) || (state == S_RDACK);
  wire        req_ready;
  wire        resp_valid;
  wire [31:0] resp_data;

  Ddr3Adapter u_adapter (
    .clock                    (clk_100m),
    .reset                    (rst_100m),
    .io_bus_req_ready         (req_ready),
    .io_bus_req_valid         (req_valid),
    .io_bus_req_bits_addr     (req_addr),
    .io_bus_req_bits_wdata    (req_wdata),
    .io_bus_req_bits_wen      (req_wen),
    .io_bus_req_bits_wstrb    (req_wstrb),
    .io_bus_req_bits_user     (68'h0),
    .io_bus_req_bits_id       (8'h0),
    .io_bus_resp_ready        (resp_ready),
    .io_bus_resp_valid        (resp_valid),
    .io_bus_resp_bits_data    (resp_data),
    .io_bus_resp_bits_user    (),
    .io_bus_resp_bits_id      (),
    .io_bus_resp_flush        (1'b0),
    .io_tileReset             (1'b0),
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

  always @(posedge clk_100m) begin
    if (rst_100m) begin
      state    <= S_IDLE;
      word_idx <= 4'd0;
      done     <= 1'b0;
      pass     <= 1'b0;
      got      <= 32'h0;
    end else begin
      case (state)
        S_IDLE: if (calib_done) state <= S_FULL_WR;
        S_FULL_WR: if (req_ready) state <= S_FULL_WRACK;
        S_FULL_WRACK: if (resp_valid) begin
          if (word_idx == 4'd3) state <= S_PW;
          else begin word_idx <= word_idx + 1; state <= S_FULL_WR; end
        end
        S_PW: if (req_ready) state <= S_PWACK;
        S_PWACK: if (resp_valid) state <= S_RD;
        S_RD: if (req_ready) state <= S_RDACK;
        S_RDACK: if (resp_valid) begin
          got   <= resp_data;
          state <= S_DONE;
        end
        S_DONE: begin
          done <= 1'b1;
          pass <= (resp_data === 32'hDEAD_F00D);
        end
        default: state <= S_IDLE;
      endcase
    end
  end

  wire all_pass = pass;
  assign tg_compare_error = !all_pass;

  initial begin
    wait (done);
    #1000;
    if (all_pass)
      $display("NZEA_PWRITE_SIM: PASS - sh 0xF00D@12 preserved high half DEAD (mergedFrom ok)");
    else
      $display("NZEA_PWRITE_SIM: FAIL got=%08x exp=%08x - mergedFrom partial-write bug!",
               got, 32'hDEAD_F00D);
    $finish;
  end

  initial begin
    wait (calib_done);
    #40_000_000;
    if (!done) begin
      $display("NZEA_PWRITE_SIM: TIMEOUT state=%0d", state);
      $finish;
    end
  end

endmodule
