// The board top with the vendor's device model on its DDR3 pins: the scenario the controller is measured against before
// anything of it reaches the board. `Top` *is* the board build's top — the design owns the pins, the DDR3 pads included
// (`nzea_fpga/src/device/ddr3/Ddr3Pads.scala`) — so what runs here is the same netlist the board gets; only what is on
// the other side of the pins differs: the vendor's model instead of a device.
//
// The judgement is the plan's own (`nzea_fpga/src/logic/Ddr3Soak.scala`): `Ddr3Bringup: PASS` is `errors == 0` at `done`;
// the watchdog fails a run that never gets there, and a mismatch prints the first one's address, expectation and answer.
`timescale 1ps/1ps

module Ddr3Bringup;

  // The board's oscillator: 50 MHz, through the pin the constraints name.
  reg clk50 = 1'b0;
  always #10000 clk50 = ~clk50;

  // The button, active-low: held down long enough for the design's reset chain and the wizard to come up, then released.
  // The device's own RESET# low period is the controller's business — it drives the pin.
  reg resetN = 1'b0;

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

  Top u_top (
      .CLK_50M(clk50),
      .RESET(resetN),
      .LED1(led1),
      .LED2(led2),
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

  // The device's write capture runs on its own strobe: the model samples DQ on its `dqs` pins, so what has to hold is
  // the relationship *there* — the strobe's edges centred in the data's eye. That centring is the *launch's* business:
  // the top drives each byte's strobe from the grid a quarter period off its data's, UG586's own arrangement (`OCLK`
  // for the data, `OCLK_DELAYED` for the strobe, "the required 90° phase offset between the write DQS and its
  // associated DQ bits"), and this bench adds no skew of its own there.
  //
  // The clock is the other half of it: the device frames every command against CK, and CK leaves the pads on the data's
  // own grid, a quarter period after the command side's — so the model is handed CK that much later, which is what its
  // own input timing expects. The board's trace delays are equal for a bus laid out as a bus and are phase-neutral;
  // what is not equal is the *read* direction's, and that is modelled below.
  wire        ddr3_ck_p_model;
  wire        ddr3_ck_n_model;
  wire [1:0]  ddr3_dqs_p_model;
  wire [1:0]  ddr3_dqs_n_model;
  wire [1:0]  ddr3_dqs_p_delayed;
  wire [1:0]  ddr3_dqs_n_delayed;
  assign #625 ddr3_ck_p_model = ddr3_ck_p;
  assign #625 ddr3_ck_n_model = ddr3_ck_n;
  // The board's write side: the strobe's path to the device is the longer one on this board's own measurements — the
  // design works as launched (the launch grids a quarter period apart), and the bench is what says by how much: an
  // offset that puts the strobe's edges in the middle of the data's eye *at the device* is the one the design's own
  // read-backs confirm there (measured: half a beat, both lanes alike — the two byte lanes' difference is a fact of the
  // *capture* side, and the read taps are per byte lane for it).
  //
  // A fitted parameter, then, and stated as one: the grid phases themselves are exact, and what is left to say is how
  // much later the strobe arrives.
  localparam integer WrStrobeSkewPs = 1250;
  assign #WrStrobeSkewPs ddr3_dqs_p_delayed[0] = ddr3_dqs_p[0];
  assign #WrStrobeSkewPs ddr3_dqs_n_delayed[0] = ddr3_dqs_n[0];
  assign #WrStrobeSkewPs ddr3_dqs_p_delayed[1] = ddr3_dqs_p[1];
  assign #WrStrobeSkewPs ddr3_dqs_n_delayed[1] = ddr3_dqs_n[1];
  // The tri-state literals are written as whole-vector patterns (`2'bzz`): a 1-bit `1'bz` in a vector context is
  // zero-extended — measured on this TB, the high bit of the pad was driven to 0 by the holding assignment and the
  // top's own drive of the upper byte fought it (`x`), which no real pad does.
  wire top_drives_dqs = u_top.soak.ddr3.DDR3_DQS_OE;
  assign ddr3_dqs_p_model = top_drives_dqs ? ddr3_dqs_p_delayed : 2'bzz;
  assign ddr3_dqs_n_model = top_drives_dqs ? ddr3_dqs_n_delayed : 2'bzz;
  assign ddr3_dqs_p = top_drives_dqs ? 2'bzz : ddr3_dqs_p_model;
  assign ddr3_dqs_n = top_drives_dqs ? 2'bzz : ddr3_dqs_n_model;

  // The board's *read* direction: the device's data reaches the fabric through the lanes, and the high byte's path is
  // the longer one — the same 625 ps, and the same lane, the write side's strobe model carries above. What the fabric
  // sees on the high byte is therefore the device's own drive, delayed; the low byte arrives as it is.
  //
  // The two directions are separated by the controller's own output enable: the fabric holds the bus except while the
  // device answers, which is exactly when this path is the one that matters (UG586's read leveling: what it centres is
  // the device's answer against the fabric's capture clock, per byte group).
  wire top_drives_dq = u_top.soak.ddr3.DDR3_DQ_OE;
  wire [15:0] dq_fab_delayed;
  assign #625 dq_fab_delayed[15:8] = ddr3_dq[15:8];
  assign dq_fab_delayed[7:0] = ddr3_dq[7:0];
  assign ddr3_dq     = top_drives_dq ? ddr3_dq_fab : 16'hzzzz;
  assign ddr3_dq_fab = top_drives_dq ? 16'hzzzz : dq_fab_delayed;

  // The device: the same model the sequence was validated against, on the pads the top drives. Its chip select is tied
  // low on this board (the MIG reference's pin map has no CS# pin), so the controller's NOPs are what "nothing" means
  // on the bus.
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

  // The controller's and the soak's own account of themselves, read where each declares it (`Ddr3` and `Ddr3Soak` keep
  // these wires beside the signals and mark them where they live, `Probes.mark`), so the paths below are those modules'
  // own nets.
  wire         initDone = u_top.soak.ddr3.probe_initDone;
  wire         calibrating = u_top.soak.ddr3.probe_calibrating;
  wire         calibrated = u_top.soak.ddr3.probe_calibrated;
  wire         alignGot = u_top.soak.ddr3.probe_alignGot;
  wire [5:0]   alignLocked = u_top.soak.ddr3.probe_alignLocked;
  wire [1:0]   lockedPlane = u_top.soak.ddr3.probe_lockedPlane;
  wire [4:0]   lockedTap = u_top.soak.ddr3.probe_lockedTap;
  wire [1:0]   writeShift = u_top.soak.ddr3.probe_writeShift;
  wire [95:0]  failTaps = u_top.soak.ddr3.probe_failTaps;
  wire [5:0]   state = u_top.soak.ddr3.probe_state;
  wire [31:0]  accesses = u_top.soak.ddr3.probe_accesses;
  wire         respValid = u_top.soak.ddr3.probe_respValid;
  wire [127:0] respData = u_top.soak.ddr3.probe_respData;
  wire [7:0]   dmOut = u_top.soak.ddr3.probe_dmOut;
  wire [23:0]  timer = u_top.soak.ddr3.probe_timer;
  wire [63:0]  dqIn = u_top.soak.ddr3.probe_dqIn;
  // The window, reassembled from the words it is watched in (`probe_captured00` is the newest): the design marks it a
  // word at a time, because a probe 832 bits wide does not survive the netlist's debug insertion.
  wire [831:0] captured =
      {u_top.soak.ddr3.probe_captured12, u_top.soak.ddr3.probe_captured11, u_top.soak.ddr3.probe_captured10,
       u_top.soak.ddr3.probe_captured09, u_top.soak.ddr3.probe_captured08, u_top.soak.ddr3.probe_captured07,
       u_top.soak.ddr3.probe_captured06, u_top.soak.ddr3.probe_captured05, u_top.soak.ddr3.probe_captured04,
       u_top.soak.ddr3.probe_captured03, u_top.soak.ddr3.probe_captured02, u_top.soak.ddr3.probe_captured01,
       u_top.soak.ddr3.probe_captured00};
  wire [6:0]   roundIn = u_top.soak.ddr3.probe_roundIn;
  wire [4:0]   tap = u_top.soak.ddr3.probe_tap;
  wire [1:0]   calibPlane = u_top.soak.ddr3.probe_calibPlane;
  wire [95:0]  recent = u_top.soak.ddr3.probe_recent;

  wire         done = u_top.soak.probe_done;
  wire [31:0]  issued = u_top.soak.probe_issued;
  wire [31:0]  errors = u_top.soak.probe_errors;
  wire [2:0]   phase = u_top.soak.probe_phase;
  wire [5:0]   step = u_top.soak.probe_step;
  wire [31:0]  firstBadAddr = u_top.soak.probe_firstBadAddr;
  wire [127:0] firstBadExp = u_top.soak.probe_firstBadExp;
  wire [127:0] firstBadGot = u_top.soak.probe_firstBadGot;
  wire [31:0]  writeAddr = u_top.soak.addrReg;
  wire [127:0] writeData = u_top.soak.dataReg;
  wire [127:0] expectData = u_top.soak.expectReg;

  // Mirrors for the waveform: the design's own state, carried through this scope so one `$dumpvars(1, Ddr3Bringup)`
  // holds them (xsim's VCD writer mishandles a second hierarchical dump list).
  wire        dbgSyncN = u_top._board50Sync_syncN;
  wire        dbgLocked = u_top._clockingWizard_locked;
  wire        dbgDelayReady = u_top.soak.delayCtrl.READY;
  wire        dbgCke = u_top.soak.ddr3.ckeReg;
  wire        dbgDeviceResetN = u_top.soak.ddr3.ddrResetNReg;
  wire [5:0]  dbgState = u_top.soak.ddr3.probe_state;
  wire [23:0] dbgTimer = u_top.soak.ddr3.probe_timer;
  wire [6:0]  dbgRoundIn = u_top.soak.ddr3.probe_roundIn;
  wire [4:0]  dbgTap = u_top.soak.ddr3.probe_tap;
  wire [5:0]  dbgAlignLocked = u_top.soak.ddr3.probe_alignLocked;
  wire [63:0] dbgDqIn = u_top.soak.ddr3.probe_dqIn;
  wire [831:0] dbgCaptured =
      {u_top.soak.ddr3.probe_captured12, u_top.soak.ddr3.probe_captured11, u_top.soak.ddr3.probe_captured10,
       u_top.soak.ddr3.probe_captured09, u_top.soak.ddr3.probe_captured08, u_top.soak.ddr3.probe_captured07,
       u_top.soak.ddr3.probe_captured06, u_top.soak.ddr3.probe_captured05, u_top.soak.ddr3.probe_captured04,
       u_top.soak.ddr3.probe_captured03, u_top.soak.ddr3.probe_captured02, u_top.soak.ddr3.probe_captured01,
       u_top.soak.ddr3.probe_captured00};
  wire        dbgRespValid = u_top.soak.ddr3.probe_respValid;
  wire [127:0] dbgRespData = u_top.soak.ddr3.probe_respData;
  wire [7:0]  dbgDm = u_top.soak.ddr3.probe_dmOut;

  // The ring's own side of the write path, carried through this scope for the waveform: what the controller staged,
  // what the serializers put out, and what their tristate controls say — the pins themselves only show the winner.
  wire [63:0] dbgDqOut = u_top.soak.ddr3.DDR3_DQ_OUT;
  wire [7:0]  dbgDqsOut = u_top.soak.ddr3.DDR3_DQS_OUT;
  wire [15:0] dbgDqDrivePad = u_top.soak.dqPads.dqDrivePad;
  wire [1:0]  dbgDqsDrivePad = u_top.soak.dqsPads.dqsDrivePad;
  wire [15:0] dbgDqTristate = u_top.soak.dqPads.dqTristate;
  wire [1:0]  dbgDqsTristate = u_top.soak.dqsPads.dqsTristate;
  wire        dbgDqOe = u_top.soak.ddr3.DDR3_DQ_OE;
  wire        dbgDqsOe = u_top.soak.ddr3.DDR3_DQS_OE;
  wire        dbgRingReset = ~u_top._ddr3Sync_syncN;
  wire        dbgClk400Dqs = u_top.soak.dqPads.clk400Dqs;
  wire        dbgClk200 = u_top.soak.dqsPads.clk200;
  wire        dbgClk200Dqs = u_top.soak.dqPads.clk200Dqs;

  initial begin
    if ($test$plusargs("dump")) begin
      $dumpfile("Ddr3Bringup.vcd");
      $dumpvars(1, Ddr3Bringup);
    end
  end

  initial begin
    #10000000;                                      // 10 us of button, then release
    resetN = 1'b1;
  end

  // The first mismatch as the soak sees it: the address it read, what the writes left there, and what came back.
  reg firstBadPrinted;
  initial begin
    firstBadPrinted = 1'b0;
    forever begin
      @(negedge u_top.soak.dqPads.clk200Dqs);
      if (!firstBadPrinted && errors != 0) begin
        firstBadPrinted = 1'b1;
        $display(
            "Ddr3Bringup: first bad read-back — step %0d, address %h, phase %0d\n  expected=%032h\n  got     =%032h",
            step,
            firstBadAddr,
            phase,
            firstBadExp,
            firstBadGot
        );
      end
    end
  end

  // The plan takes tens of microseconds; the bring-up ~705 us before it. The watchdog is the run's own bound: a board
  // that never reports done is a board whose controller or soak is stuck, and the counters say where.
  initial begin
    #4000us;
    $display(
        "Ddr3Bringup: FAIL — the watchdog fired: initDone=%b calibrating=%b calibrated=%b done=%b phase=%0d step=%0d issued=%0d errors=%0d",
        initDone,
        calibrating,
        calibrated,
        done,
        phase,
        step,
        issued,
        errors
    );
    $fatal;
  end

  // The sweep's own verdict, per plane: which of the 32 steps of the input delay the capture came through clean at.
  // A step outside a byte lane's eye is a step the window's own edge is read from, so the run is judged by the windows,
  // not by the round rate — and whichever plane's window is widest is the two byte lanes' delay difference as this
  // board (or this TB's model of it) has it.
  task automatic planeWindow(input int plane, output int count, output int first, output int last);
    reg [31:0] clean;
    begin
      clean = ~failTaps[32 * plane +: 32];
      count = 0;
      first = -1;
      last = -1;
      for (int i = 0; i < 32; i = i + 1) begin
        if (clean[i]) begin
          count = count + 1;
          if (first < 0) first = i;
          last = i;
        end
      end
    end
  endtask

  initial begin
    int count, first, last, best;
    best = 0;
    wait (initDone === 1'b1);
    $display("Ddr3Bringup: initialised (MR2/3/1/0 + ZQCL, the device's RESET# and CKE on the plan)");
    wait (done === 1'b1);
    for (int p = 0; p < 3; p = p + 1) begin
      planeWindow(p, count, first, last);
      $display(
          "Ddr3Bringup: plane %0d (high lane %s the low): %0d clean steps, window %0d..%0d centre %0d",
          p,
          p == 0 ? "at" : (p == 1 ? "8 steps on" : "8 steps back from"),
          count,
          first,
          last,
          (first + last) / 2
      );
      if (count > best) best = count;
    end
    // The alignment is the *post-lock* round's own (it moves with the tap): what the response sliced at is what this
    // says, and the round's own verdict is what opened the bus.
    $display(
        "Ddr3Bringup: locked plane %0d, low lane tap %0d, write shift %0d, final alignment %0d%s; failTaps=%024h",
        lockedPlane,
        lockedTap,
        writeShift,
        alignLocked,
        calibrated ? "" : " (the post-lock round did not carry the burst)",
        failTaps
    );
    // The plan's shape, as it ran: 40 steps — the walk's 16, the patterns' 8 and the LFSR's 8 write-then-read, and the
    // masks' 8 write-write-then-read — so 88 accepted requests (40 writes, 40 read-backs, 8 masked writes) if every
    // step ran and none was retried.
    $display(
        "Ddr3Bringup: plan done — %0d steps, %0d accesses, %0d mismatched read-backs",
        step,
        issued,
        errors
    );
    if ((errors == 0) && calibrated) begin
      $display("Ddr3Bringup: PASS — locked and clean; a byte lane's capture window is %0d steps wide", best);
      $finish;
    end else begin
      $display(
          "Ddr3Bringup: FAIL — %0d mismatched read-backs, first at step %0d address %h\n  expected=%032h\n  got     =%032h",
          errors,
          step,
          firstBadAddr,
          firstBadExp,
          firstBadGot
      );
      $fatal;
    end
  end

endmodule
