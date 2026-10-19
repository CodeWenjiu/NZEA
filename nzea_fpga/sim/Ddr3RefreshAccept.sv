// The refresh/accept race as its own scenario: a bus access accepted on the very cycle the refresh timer falls due.
//
// `Ddr3` reads its accept out of the handshake one cycle late (`busTaken`), and its idle branch used to let a refresh
// that fell due in that cycle start *instead* of the accepted access. The accept was then consumed by the refresh: the
// access never ran, its adapter waited for an answer nothing would send, and every master behind it wedged for good.
// Measured on the board: the loader stopped at word 1739 of 2312 with the controller idle and its counter equal to the
// loader's — once in ~1500 accepted words (the timer's interval).
//
// The bench drives the controller's own bus port — the piece the race lives in — with the loader and the core quiet.
// A control write accepted at a safe phase proves the driving works; the aligned write, accepted exactly where the
// timer falls due the next cycle (`refTimer == 1499`), must be answered. On the buggy order it never is, and the
// missing answer is what this scenario reports; with the handshake's own order — an accepted access is never
// interrupted — it is answered like any other.
`timescale 1ps/1ps

module Ddr3RefreshAccept;

  localparam integer REFRESH_INTERVAL = 1500;

  reg clk50 = 1'b0;
  always #10000 clk50 = ~clk50;

  // The button, held long enough for the reset chain and the wizard, then released; the line idles (the loader is not
  // used here — this scenario reaches the controller directly).
  reg resetN = 1'b0;
  reg uartRx = 1'b1;

  wire led1;
  wire led2;

  wire        ddr3_ck_p;
  wire        ddr3_ck_n;
  wire [14:0] ddr3_addr;
  wire [2:0]  ddr3_ba;
  wire        ddr3_ras_n;
  wire        ddr3_cas_n;
  wire        ddr3_we_n;
  wire        ddr3_cke;
  wire        ddr3_odt;
  wire        ddr3_reset_n;
  wire [1:0]  ddr3_dm;
  wire [15:0] ddr3_dq;          // the device's side of the bus (the model's own pads)
  wire [15:0] ddr3_dq_fab;      // the fabric's side of the bus (the pads at the top)
  wire [1:0]  ddr3_dqs_p;
  wire [1:0]  ddr3_dqs_n;
  wire [1:0]  ddr3_tdqs_n;

  Top dut (
      .CLK_50M(clk50),
      .RESET(resetN),
      .LED1(led1),
      .LED2(led2),
      .UART_RX(uartRx),
      .DDR3_CK_P(ddr3_ck_p),
      .DDR3_CK_N(ddr3_ck_n),
      .DDR3_ADDR(ddr3_addr),
      .DDR3_BA(ddr3_ba),
      .DDR3_RAS_N(ddr3_ras_n),
      .DDR3_CAS_N(ddr3_cas_n),
      .DDR3_WE_N(ddr3_we_n),
      .DDR3_CKE(ddr3_cke),
      .DDR3_ODT(ddr3_odt),
      .DDR3_RESET_N(ddr3_reset_n),
      .DDR3_DM(ddr3_dm),
      .DDR3_DQ(ddr3_dq_fab),
      .DDR3_DQS_P(ddr3_dqs_p),
      .DDR3_DQS_N(ddr3_dqs_n)
  );

  // The bus's idle level, while neither side drives it: on the board the data lines are terminated (the fabric's
  // IN_TERM, VREF-referenced) so a floating line reads as a level, and the compare sees levels here too instead of x.
  genvar bit_i;
  generate
    for (bit_i = 0; bit_i < 16; bit_i = bit_i + 1) begin : pull_data
      pullup(ddr3_dq[bit_i]);
    end
  endgenerate
  pullup(ddr3_dqs_p[0]);
  pullup(ddr3_dqs_p[1]);
  pulldown(ddr3_dqs_n[0]);
  pulldown(ddr3_dqs_n[1]);

  // The bench's own model of the board's traces, as `Ddr3Bringup` states it: CK reaches the device 625 ps after it left
  // the pads (the data grid's quarter period), the write strobe reaches it 1250 ps later (the centring the design
  // launches for), and the read direction's high byte is the longer path — the same 625 ps, on the same lane.
  wire        ddr3_ck_p_model;
  wire        ddr3_ck_n_model;
  wire [1:0]  ddr3_dqs_p_model;
  wire [1:0]  ddr3_dqs_n_model;
  wire [1:0]  ddr3_dqs_p_delayed;
  wire [1:0]  ddr3_dqs_n_delayed;
  assign #625 ddr3_ck_p_model = ddr3_ck_p;
  assign #625 ddr3_ck_n_model = ddr3_ck_n;
  localparam integer WrStrobeSkewPs = 1250;
  assign #WrStrobeSkewPs ddr3_dqs_p_delayed[0] = ddr3_dqs_p[0];
  assign #WrStrobeSkewPs ddr3_dqs_n_delayed[0] = ddr3_dqs_n[0];
  assign #WrStrobeSkewPs ddr3_dqs_p_delayed[1] = ddr3_dqs_p[1];
  assign #WrStrobeSkewPs ddr3_dqs_n_delayed[1] = ddr3_dqs_n[1];
  wire top_drives_dqs = dut.memory.ddr3.DDR3_DQS_OE;
  assign ddr3_dqs_p_model = top_drives_dqs ? ddr3_dqs_p_delayed : 2'bzz;
  assign ddr3_dqs_n_model = top_drives_dqs ? ddr3_dqs_n_delayed : 2'bzz;
  assign ddr3_dqs_p = top_drives_dqs ? 2'bzz : ddr3_dqs_p_model;
  assign ddr3_dqs_n = top_drives_dqs ? 2'bzz : ddr3_dqs_n_model;

  wire top_drives_dq = dut.memory.ddr3.DDR3_DQ_OE;
  wire [15:0] dq_fab_delayed;
  assign #625 dq_fab_delayed[15:8] = ddr3_dq[15:8];
  assign dq_fab_delayed[7:0] = ddr3_dq[7:0];
  assign ddr3_dq     = top_drives_dq ? ddr3_dq_fab : 16'hzzzz;
  assign ddr3_dq_fab = top_drives_dq ? 16'hzzzz : dq_fab_delayed;

  // The device: the vendor's model on the pads the top drives. Its chip select is tied low on this board (the MIG
  // reference's pin map has no CS# pin), so the controller's NOPs are what "nothing" means on the bus.
  ddr3 u_mem (
      .rst_n   (ddr3_reset_n),
      .ck      (ddr3_ck_p_model),
      .ck_n    (ddr3_ck_n_model),
      .cke     (ddr3_cke),
      .cs_n    (1'b0),
      .ras_n   (ddr3_ras_n),
      .cas_n   (ddr3_cas_n),
      .we_n    (ddr3_we_n),
      .dm_tdqs (ddr3_dm),
      .ba      (ddr3_ba),
      .addr    (ddr3_addr),
      .dq      (ddr3_dq),
      .dqs     (ddr3_dqs_p_model),
      .dqs_n   (ddr3_dqs_n_model),
      .tdqs_n  (ddr3_tdqs_n),
      .odt     (ddr3_odt)
  );

  // The request the bench presents to the controller. The nets are forced once (xsim drops a second force on the same
  // net silently), and the registers behind the forces are what moves them.
  reg         req_valid = 1'b0;
  reg [31:0]  req_addr  = 32'h0000_0000;
  reg [127:0] req_wdata = 128'h0;
  reg         req_wen   = 1'b1;
  reg [15:0]  req_wstrb = 16'hffff;
  reg         req_user  = 1'b0;
  reg         req_id    = 1'b0;

  initial begin
    force dut.memory.ddr3.bus_req_valid      = req_valid;
    force dut.memory.ddr3.bus_req_bits_addr  = req_addr;
    force dut.memory.ddr3.bus_req_bits_wdata = req_wdata;
    force dut.memory.ddr3.bus_req_bits_wen   = req_wen;
    force dut.memory.ddr3.bus_req_bits_wstrb = req_wstrb;
    force dut.memory.ddr3.bus_req_bits_user  = req_user;
    force dut.memory.ddr3.bus_req_bits_id    = req_id;
  end

  // Every answer the controller gives, caught as it goes by (the adapter takes each one in the cycle it rises).
  reg answered_seen = 1'b0;
  always @(posedge dut.memory.clk200_45) begin
    if (dut.memory.ddr3.bus_resp_valid === 1'b1) answered_seen <= 1'b1;
  end

  task automatic check(input bit ok, input string what);
    if (!ok) begin
      $display("Ddr3RefreshAccept: FAIL — %s", what);
      $fatal;
    end
  endtask

  // One write, accepted exactly in the cycle the controller's refresh timer reads `target`: the timed phase is sampled
  // one cycle earlier (where the timer reads `target - 1`) and the request is presented for the cycle that follows.
  task automatic one_write(input integer target, input string what, output integer cycles);
    integer guard;
    bit     found;
    guard = 0;
    found = 1'b0;
    while (!found) begin
      @(posedge dut.memory.clk200_45);
      guard = guard + 1;
      if (dut.memory.ddr3.refTimer == target - 1 && dut.memory.ddr3.bus_req_ready === 1'b1) found = 1'b1;
      else if (guard > 4 * REFRESH_INTERVAL) begin
        $display(
            "Ddr3RefreshAccept: FAIL — %s: no aligned phase (state=%0d timer=%0d)",
            what,
            dut.memory.ddr3.probe_state,
            dut.memory.ddr3.refTimer
        );
        $fatal;
      end
    end

    // The accept cycle itself: the request stands for exactly the cycle whose timer reads `target`.
    answered_seen = 1'b0;
    req_valid = 1'b1;
    #1;
    check(dut.memory.ddr3.bus_req_ready === 1'b1, "the controller was not ready in the accept cycle");
    @(posedge dut.memory.clk200_45);
    req_valid = 1'b0;

    // The answer: an access is tens of cycles; past the bound it is never coming.
    cycles = 0;
    while (!answered_seen && cycles < 4000) begin
      @(posedge dut.memory.clk200_45);
      cycles = cycles + 1;
    end
    if (!answered_seen) begin
      $display(
          "Ddr3RefreshAccept: FAIL — %s: the access accepted at refTimer=%0d was never answered (state=%0d timer=%0d calibrated=%b)",
          what,
          target,
          dut.memory.ddr3.probe_state,
          dut.memory.ddr3.refTimer,
          dut.memory.ddr3.probe_calibrated
      );
      $fatal;
    end
  endtask

  integer control_cycles;
  integer aligned_cycles;

  initial begin
    #10000000;  // 10 us of button, then release
    resetN = 1'b1;

    // The memory comes up before anything may touch it: the controller initializes the device and measures its read
    // path, and the bus only answers after that verdict.
    wait (dut.memory.ddr3.probe_calibrated === 1'b1);

    // The control: a write accepted at a safe phase — the driving itself works.
    one_write(100, "control write", control_cycles);
    $display("Ddr3RefreshAccept: control write (accept at refTimer=100) answered after %0d cycles", control_cycles);

    // The race: accepted at refTimer=1499, so the refresh falls due in the very cycle the accept is read.
    one_write(1499, "aligned write", aligned_cycles);
    $display("Ddr3RefreshAccept: aligned write (accept at refTimer=1499) answered after %0d cycles", aligned_cycles);

    $display("Ddr3RefreshAccept: PASS — an access accepted on the eve of a refresh is served");
    $finish;
  end

  // The bring-up is well under a millisecond here; anything past this means the design never got there.
  initial begin
    #10ms;
    $display(
        "Ddr3RefreshAccept: FAIL — the watchdog fired: calibrated=%b state=%0d",
        dut.memory.ddr3.probe_calibrated,
        dut.memory.ddr3.probe_state
    );
    $fatal;
  end
endmodule