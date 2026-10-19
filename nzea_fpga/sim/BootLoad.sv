// The loader's scenario: the board top, the vendor's memory on its DDR3 pins, a serial line and the program the line
// carries.
//
// The board build's own top is the design under test (`nzea_fpga/src/Top.scala`) — the same netlist the bitstream is
// made from, the vendor IP included — and what stands in for the host is this bench's line driver: the bytes it sends
// are exactly the frame `probe load` would put on the board's bridge ([[probe]]'s `protocol.rs`).
//
// What the run proves is the whole boot path the board will take: the loader's frame goes in at the pin, through the
// crossing and the adapter, into the memory the vendor's model answers for, and the *core* — released only after the
// frame's last word landed and the controller's calibration locked — fetches its words back out of that memory. The
// verdict is the program's own: it writes the finisher, whose level the top spends on LED2 and the ILA watches. The
// reset-only command (`size = 0`) must restart the resident program, which is the same path a second time with no word
// written.
//
// The words past the program stay unwritten — the model's own powered-up contents — and the core speculates into them
// after the spin it was loaded with; the checks below ask only for what the loader promises.
`timescale 1ps/1ps

module BootLoad;

  // One line bit at 115200 baud, in the bench's own unit: the timescale is 1 ps, so the period is 1e12 / 115200 ps —
  // written as sized 64-bit literals, since an unsized decimal constant is read as 32 bits and wraps (which turns the
  // delay zero). The design's own divider counts 25 MHz / 115200 = 217 cycles of 40 ns, and the receiver's centre
  // sampling absorbs the 0.006 % difference.
  localparam longint BitPs = 64'd1000000000000 / 64'd115200;
  localparam integer Words = 4;

  reg clk50 = 1'b0;
  always #10000 clk50 = ~clk50;

  // The button, active-low: held long enough for the reset chain and the wizard, then released.
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

  // `nzea_core/test/asm/boot_finish.S`, assembled to four words: write the finisher (`0x00100000`) and spin. The
  // checked-in source is the truth; this copy exists so the bench needs no toolchain and no file beside it.
  reg [31:0] prog[0:Words-1];
  initial begin
    prog[0] = 32'h001002b7;  // lui  t0, 0x100
    prog[1] = 32'h00100313;  // addi t1, x0, 1
    prog[2] = 32'h0062a023;  // sw   t1, 0(t0)
    prog[3] = 32'h0000006f;  // j    .
  end

  task automatic check(input bit ok, input string what);
    if (!ok) begin
      $display("BootLoad: FAIL — %s", what);
      $fatal;
    end
  endtask

  task automatic sendByte(input [7:0] b);
    integer i;
    begin
      uartRx = 1'b0;  // start
      #BitPs;
      for (i = 0; i < 8; i = i + 1) begin
        uartRx = b[i];  // LSB first
        #BitPs;
      end
      uartRx = 1'b1;  // stop
      #BitPs;
    end
  endtask

  task automatic sendWord(input [31:0] w);
    begin
      sendByte(w[7:0]);
      sendByte(w[15:8]);
      sendByte(w[23:16]);
      sendByte(w[31:24]);
    end
  endtask

  task automatic sendHeader(input [31:0] addr, input [31:0] size);
    begin
      sendWord(32'hB007B007);
      sendWord(addr);
      sendWord(size);
    end
  endtask

  // Wait for the program's verdict, counted in the ILA's own clock so a stalled core fails here rather than hanging.
  task automatic waitFinished(input string what);
    integer cycles;
    begin
      cycles = 0;
      while (dut.probe_finished !== 1'b1 && cycles < 400000) begin
        @(posedge clk50);
        cycles = cycles + 1;
      end
      check(dut.probe_finished === 1'b1, what);
    end
  endtask

  integer i;
  initial begin
    if ($test$plusargs("dump")) begin
      $dumpfile("BootLoad.vcd");
      $dumpvars(1, BootLoad.dut.tile);
      $dumpvars(1, BootLoad.dut.tile.core);
      $dumpvars(1, BootLoad.dut.memory);
      $dumpvars(0, BootLoad.dut.boot);
    end
  end

  initial begin
    #10000000;  // 10 us of button, then release
    resetN = 1'b1;

    // The memory comes up before anything may write it: the controller initializes the device and measures its own read
    // path (the tap sweep), and both the loader's and the core's domains wait for that verdict (`ddr3Ready`).
    wait (dut.memory.ddr3.probe_calibrated === 1'b1);

    // The loader's own domain is up (the button, the generator's lock and the memory), and the core is still held:
    // there is no program in the memory before the first load.
    wait (dut._bootSync_syncN === 1'b1);
    check(dut._coreSync_syncN === 1'b0, "the core is held before the first load");
    check(dut.probe_finished === 1'b0, "nothing has finished before the first load");

    // A load: the magic, the address the program is linked at, its length and its words.
    sendHeader(32'h80000000, Words);
    for (i = 0; i < Words; i = i + 1) sendWord(prog[i]);

    // The last word's acknowledge is what releases the core, and running the program is what raises the finisher.
    wait (dut._coreSync_syncN === 1'b1);
    waitFinished("the loaded program did not write the finisher");

    // The reset-only command: the magic, the address, a zero length. Its hold starts *with the magic* — that hold is the
    // core's reset, which is what clears the finisher with its domain — and the release that follows the size word
    // restarts the resident program from the reset vector.
    sendWord(32'hB007B007);
    wait (dut._coreSync_syncN === 1'b0);
    repeat (4) @(posedge clk50);
    check(dut.probe_finished === 1'b0, "the reset-only command did not clear the finisher with the core");
    sendWord(32'h80000000);
    sendWord(0);
    wait (dut._coreSync_syncN === 1'b1);
    waitFinished("the resident program did not run again after the reset-only command");

    $display("BootLoad: PASS — %0d words through the line and the DDR, the program ran, was reset and ran again", Words);
    $finish;
  end

  // Two frames are ~4.5 ms of line time (a frame is 28 bytes at 115200), the bring-up ~0.75 ms, and the programs' own
  // runs are fast; the watchdog is the run's own bound on a design that never gets there.
  initial begin
    #7ms;
    $display(
        "BootLoad: FAIL — the watchdog fired: calibrated=%b initDone=%b bootState=%0d bootWritten=%0d bootWords=%0d bootHoldN=%b bootReset=%b coreReset=%b finished=%b",
        dut.memory.ddr3.probe_calibrated,
        dut.memory.ddr3.probe_initDone,
        dut.boot.probe_bootState,
        dut.boot.probe_bootWritten,
        dut.boot.probe_bootWords,
        dut.probe_bootHoldN,
        dut.probe_bootReset,
        dut.probe_coreReset,
        dut.probe_finished
    );
    $fatal;
  end
endmodule
