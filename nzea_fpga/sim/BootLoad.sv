// The loader's scenario: the board top, a serial line and the program the line carries.
//
// The board build's own top is the design under test (`nzea_fpga/src/Top.scala`) — the same netlist the bitstream is
// made from, the vendor IP included — and what stands in for the host is this bench's line driver: the bytes it sends
// are exactly the frame `probe load` would put on the board's bridge ([[probe]]'s `protocol.rs`).
//
// The verdict is the program's own: after the frame the core must leave reset, run the four words the frame carried,
// and write the finisher — whose level the top spends on LED2 and the ILA watches. The reset-only command (`size = 0`)
// must restart the resident program, which is the same path a second time with no word written.
//
// The words past the program stay unwritten — X in this bench, whatever the macro powered up with on a board — and the
// core speculates into them after the spin it was loaded with; in simulation that X reaches its state, after the verdict
// and never before it. The checks below ask only for what the loader promises, which is why the X's arrival is not a
// failure.
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

  Top dut (
      .CLK_50M(clk50),
      .RESET(resetN),
      .LED1(led1),
      .LED2(led2),
      .UART_RX(uartRx)
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
      while (dut.probe_finished !== 1'b1 && cycles < 100000) begin
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

    // The loader's own domain is up (the button and the generator's lock), and the core is still held: there is no
    // program in the memory before the first load.
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
    // restarts the resident program from the reset vector. The few cycles after the hold are the core's own reset taking
    // with its domain's edge.
    sendWord(32'hB007B007);
    wait (dut._coreSync_syncN === 1'b0);
    repeat (4) @(posedge clk50);
    check(dut.probe_finished === 1'b0, "the reset-only command did not clear the finisher with the core");
    sendWord(32'h80000000);
    sendWord(0);
    wait (dut._coreSync_syncN === 1'b1);
    waitFinished("the resident program did not run again after the reset-only command");

    $display("BootLoad: PASS — %0d words, the program ran, was reset and ran again", Words);
    $finish;
  end

  // The two frames are ~2 ms of line time; the watchdog is the run's own bound on a design that never gets there.
  initial begin
    #5ms;
    $display(
        "BootLoad: FAIL — the watchdog fired: bootState=%0d bootWritten=%0d bootWords=%0d bootHoldN=%b bootReset=%b coreReset=%b finished=%b",
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
