// The first DDR3 scenario: bring the board's memory (the vendored Micron model, MT41K256M16TW-107) up through its
// JEDEC initialisation and prove one write/read round trip. It drives the pins the way a controller must — there is no
// controller yet, on purpose: this is the known-good reference sequence the controller will be measured against, and
// the model's own timing checks are what validate it (500 us before CKE, tMRD/tMOD spacing, tZQinit, tRCD, tWR).
//
// The model's prints are the teeth: an `ERROR` line in the log is a violation. `-testplusarg dump` writes
// `Ddr3Smoke.vcd` (the whole device is dumped; it is small next to the board top); a clean run ends with the PASS line.
`timescale 1ps/1ps

module Ddr3Smoke;

  // 400 MHz memory clock (tCK = 2500 ps, DDR3-800): far inside the part's sg107 limit, and a comfortable rate to
  // bring a controller up at.
  localparam TCK = 2500;
  localparam UI  = TCK / 2;         // one data beat per half clock

  reg ck = 1'b0;
  always #(UI) ck = ~ck;
  wire ck_n = ~ck;

  // The device's pins, driven the way the controller will drive them.
  reg        rst_n  = 1'b0;
  reg        cke    = 1'b0;
  reg        cs_n   = 1'b1;
  reg        ras_n  = 1'b1;
  reg        cas_n  = 1'b1;
  reg        we_n   = 1'b1;
  reg        odt    = 1'b0;
  reg [14:0] addr   = 15'b0;
  reg [2:0]  ba     = 3'b0;
  reg [15:0] dq_out = 16'b0;
  reg        dq_oe  = 1'b0;
  reg [1:0]  dqs_out = 2'b00;
  reg        dqs_oe  = 1'b0;

  wire [15:0] dq;
  wire [1:0]  dqs;
  wire [1:0]  dqs_n;
  wire [1:0]  tdqs_n;
  wire [1:0]  dm_tdqs;

  assign dq      = dq_oe  ? dq_out  : 16'hzzzz;
  assign dqs     = dqs_oe ? dqs_out : 2'bzz;
  assign dqs_n   = dqs_oe ? ~dqs_out : 2'bzz;
  assign dm_tdqs = 2'b00;                          // data mask: never masked

  ddr3 u_mem (
      .rst_n   (rst_n),
      .ck      (ck),
      .ck_n    (ck_n),
      .cke     (cke),
      .cs_n    (cs_n),
      .ras_n   (ras_n),
      .cas_n   (cas_n),
      .we_n    (we_n),
      .dm_tdqs (dm_tdqs),
      .ba      (ba),
      .addr    (addr),
      .dq      (dq),
      .dqs     (dqs),
      .dqs_n   (dqs_n),
      .tdqs_n  (tdqs_n),
      .odt     (odt)
  );

  // The model's write accounting, read through the TB's own scope so the single `$dumpvars(1, Ddr3Smoke)` carries them:
  // its DQS edge timestamps, its expectation/capture counters and the check windows.
  wire [63:0] dbg_tm_dqs_pos0   = u_mem.tm_dqs_pos[0];
  wire [63:0] dbg_tm_dqs_neg0   = u_mem.tm_dqs_neg[0];
  wire [31:0] dbg_wdqs_cntr     = u_mem.wdqs_cntr;
  wire [31:0] dbg_wdqs_pos_cnt0 = u_mem.wdqs_pos_cntr[0];
  wire        dbg_dq_in_valid   = u_mem.dq_in_valid;
  wire        dbg_dqs_in_valid  = u_mem.dqs_in_valid;
  wire        dbg_chk_high0     = u_mem.check_write_dqs_high[0];
  wire        dbg_chk_low0      = u_mem.check_write_dqs_low[0];

  // The mode registers for this rate: BL8, CL6, WR6, DLL enabled with RZQ/6 output drive and Rtt_Nom, no MPR.
  localparam [14:0] MR0 = 15'h0520;
  localparam [14:0] MR1 = 15'h0044;
  localparam [14:0] MR2 = 15'h0000;
  localparam [14:0] MR3 = 15'h0000;
  localparam integer CL  = 6;
  localparam integer CWL = 5;

  reg [15:0] wr_words [0:7];
  reg [15:0] rd_words [0:7];

  task mrs(input [2:0] bank, input [14:0] value);
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b0; cas_n = 1'b0; we_n = 1'b0; ba = bank; addr = value;
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
    end
  endtask

  task act(input [2:0] bank, input [14:0] row);
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b0; cas_n = 1'b1; we_n = 1'b1; ba = bank; addr = row;
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
    end
  endtask

  task pre_all;
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b0; cas_n = 1'b1; we_n = 1'b0; ba = 3'b0; addr = 15'h0400;
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
    end
  endtask

  task zqcl;
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b0; ba = 3'b0; addr = 15'h0400;
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
    end
  endtask

  // A BL8 write: the command, then eight 16-bit beats on the UI grid, DQS crossing at each beat's centre (the model
  // latches DQ on both DQS edges). The strobe train leads with a fall one UI ahead of the first beat: the model anchors
  // the burst on that fall (its `WDQS_PRE` window wants a high-to-low edge before the first centre crossing), so DQS is
  // held high through the preamble and the train opens by falling into it.
  task wr_burst(input [2:0] bank, input [9:0] col);
    integer k;
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b1; cas_n = 1'b0; we_n = 1'b0; ba = bank; addr = {5'b0, col};
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
      @(posedge ck);
      dqs_oe = 1'b1; dqs_out = 2'b11;
      repeat (3) @(negedge ck);                       // mid-preamble
      #(UI + UI/2);                                   // t0 - UI/2
      dqs_out = 2'b00;                                // the opening fall: the model's first strobe edge
      #(UI/2);                                        // t0: the first beat
      #1;
      dq_oe = 1'b1;
      for (k = 0; k < 8; k = k + 1) begin
        dq_out = wr_words[k];
        #(UI/2); dqs_out = ~dqs_out;                  // the beat's centre
        #(UI/2);
      end
      dq_oe = 1'b0;
      #(TCK);                                         // postamble
      dqs_oe = 1'b0;
    end
  endtask

  // A BL8 read: capture each beat at its centre, RL = CL clocks after the command's latch edge.
  task rd_burst(input [2:0] bank, input [9:0] col);
    integer k;
    begin
      @(negedge ck); cs_n = 1'b0; ras_n = 1'b1; cas_n = 1'b0; we_n = 1'b1; ba = bank; addr = {5'b0, col};
      @(negedge ck); cs_n = 1'b1; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
      @(posedge ck);                                  // one clock after the latch edge
      repeat (CL - 1) @(posedge ck);                  // ... CL in all: t0, the first beat
      for (k = 0; k < 8; k = k + 1) begin
        #(UI/2); rd_words[k] = dq;
        #(UI/2);
      end
    end
  endtask

  integer k;

  initial begin
    if ($test$plusargs("dump")) begin
      $dumpfile("Ddr3Smoke.vcd");
      // One plain call: xsim's VCD writer mishandles additional hierarchical dump lists (it allocates ids and emits
      // values without the matching declarations, which corrupts the file), so the model's accounting is mirrored
      // into the TB's own scope on the wires below instead.
      $dumpvars(1, Ddr3Smoke);
    end
  end

  initial begin
    #2ms;
    $display("Ddr3Smoke: FAIL — the watchdog fired; the scenario never completed");
    $fatal;
  end

  initial begin : scenario
    // The model's own rules: RST_N low for 200 us (the JEDEC power-up rule it checks), then 500 us before CKE
    // may go active.
    #200000000;                                      // 200 us of reset
    rst_n = 1'b1;
    #500000000;                                      // 500 us, RST_N inactive to CKE active
    @(negedge ck); cke = 1'b1;                       // CKE rises with a NOP on the bus
    cs_n = 1'b0; ras_n = 1'b1; cas_n = 1'b1; we_n = 1'b1;
    repeat (300) @(posedge ck);                      // tXPR before the first MRS

    // The mode registers (MR2 -> MR3 -> MR1 -> MR0), then a ZQ calibration.
    mrs(3'd2, MR2); repeat (8) @(posedge ck);
    mrs(3'd3, MR3); repeat (8) @(posedge ck);
    mrs(3'd1, MR1); repeat (8) @(posedge ck);
    mrs(3'd0, MR0); repeat (550) @(posedge ck);      // tMOD + tDLLK after the DLL reset
    zqcl;           repeat (650) @(posedge ck);      // tZQinit (598 tCK at sg107's TCK_MIN)

    // One burst written and read back: activate, write, precharge, activate, read.
    for (k = 0; k < 8; k = k + 1) wr_words[k] = 16'hA000 + k;
    act(3'd0, 15'd0);    repeat (8) @(posedge ck);   // tRCD
    wr_burst(3'd0, 10'd0);
    repeat (8) @(posedge ck);                        // tWR before the precharge
    pre_all;             repeat (8) @(posedge ck);   // tRP
    act(3'd0, 15'd0);    repeat (8) @(posedge ck);   // tRCD
    rd_burst(3'd0, 10'd0);
    repeat (4) @(posedge ck);

    for (k = 0; k < 8; k = k + 1) begin
      if (rd_words[k] !== wr_words[k]) begin
        $display("Ddr3Smoke: FAIL — beat %0d: wrote %04h, read %04h", k, wr_words[k], rd_words[k]);
        $fatal;
      end
    end
    $display("Ddr3Smoke: PASS — initialised (MR2/3/1/0 + ZQCL) and one BL8 burst round-tripped at 400 MHz");
    $finish;
  end

endmodule
