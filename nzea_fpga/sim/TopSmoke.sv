// xsim smoke scenario for the board top — the first of `nzea_fpga/sim/`, and the shape the others follow: a
// self-checking testbench that owns the board's pins (oscillator, button) and judges the design against the official
// IP models the flow compiles in (the clocking wizard's sim netlist).
//
// What it checks: the wizard's model locks, the reset synchronizer holds both blinkers at zero while the button is
// down, and the two domains count at the ratio their clocks imply (the 50 MHz blinker against the 100 MHz output).
// Self-checking: a failure is `$fatal` — the recipe fails with it; a watchdog fails a run that would otherwise hang.
// `-testplusarg dump` writes `TopSmoke.vcd` for pulse. The flow requires the `TopSmoke: PASS` line below.
// The blinkers' instances are `dut.blinkers.blink50` / `dut.blinkers.blink100` (`nzea_fpga/src/logic/Blinkers.scala`):
// `dut` is the board's top (`Top`), which owns the pins — the DDR3 pads included.
`timescale 1ns/1ps

module TopSmoke;

  // The board: a 50 MHz oscillator, and the push button (active-low — zero is pressed, which asserts reset).
  reg CLK_50M = 1'b0;
  always #10 CLK_50M = ~CLK_50M;

  reg RESET = 1'b0;

  wire LED1;
  wire LED2;
  Top dut (
      .CLK_50M(CLK_50M),
      .RESET  (RESET),
      .LED1   (LED1),
      .LED2   (LED2)
  );

  // The verdict: one condition, judged where the scenario sees it. A false one fails the run with what was seen.
  task check(input logic ok, input string what);
    if (ok !== 1'b1) begin
      $display("TopSmoke: FAIL — %s", what);
      $fatal;
    end
  endtask

  // Waveform dumping is opt-in (the flow passes the plusarg): the whole top is small, so dump it whole.
  initial begin
    if ($test$plusargs("dump")) begin
      $dumpfile("TopSmoke.vcd");
      $dumpvars(1, TopSmoke);
      $dumpvars(0, dut);
    end
  end

  // A run that neither passes nor fails must fail, not hang the recipe.
  initial begin
    #10ms;
    $display("TopSmoke: FAIL — the watchdog fired; the scenario never completed");
    $fatal;
  end

  int          cycles;
  logic [25:0] c50_0, c50_1, c100_0, c100_1;

  initial begin
    // The button is held: reset is out, so both blinkers hold at zero and the wizard — reset while the button is
    // down — reports no lock.
    repeat (10) @(posedge CLK_50M);
    check(
      dut.blinkers.blink50.count === 26'd0 && dut.blinkers.blink100.count === 26'd0,
      $sformatf("the blinkers count while the button is held (%0d, %0d)", dut.blinkers.blink50.count,
          dut.blinkers.blink100.count)
    );
    check(dut.clockingWizard.locked === 1'b0, "the wizard reports lock while the button is held");

    // Release the button and wait for the wizard's model to lock.
    RESET = 1'b1;
    cycles = 0;
    while (dut.clockingWizard.locked !== 1'b1) begin
      @(posedge CLK_50M);
      if (cycles++ > 100000)
        check(1'b0, $sformatf("the wizard never locked (%0d cycles)", cycles));
    end

    // The counters: the 50 MHz blinker advances one per cycle of this clock; the 100 MHz one two per cycle of it.
    // Both are sampled at this scenario's edges — the first is exact (same clock), the second crosses into the
    // wizard's domain, so the count over 100 edges lands within one edge of 200.
    c50_0  = dut.blinkers.blink50.count;
    c100_0 = dut.blinkers.blink100.count;
    repeat (100) @(posedge CLK_50M);
    c50_1  = dut.blinkers.blink50.count;
    c100_1 = dut.blinkers.blink100.count;

    check(
      c50_1 - c50_0 === 26'd100,
      $sformatf("blink50 advanced %0d in 100 cycles, expected 100", c50_1 - c50_0)
    );
    check(
      (c100_1 - c100_0) >= 26'd199 && (c100_1 - c100_0) <= 26'd201,
      $sformatf("blink100 advanced %0d in 100 cycles, expected ~200", c100_1 - c100_0)
    );

    $display("TopSmoke: PASS — locked, and both domains count (50 MHz +100, 100 MHz ~+200 per 100 cycles)");
    $finish;
  end

endmodule
