// The wizard's own phase behaviour under the simulation model, measured so a phase plan can be compared against what
// the board's silicon does with the same parameters (`Ddr3Clocks` is the plan; this is the model's half).
//
// What is printed, per output: the time from a reference `clk_in1` edge to the output's first rising edge, and the
// following period — both in picoseconds. The *relative* offsets are the numbers a phase plan reads: with the design's
// own plan (200@0, 200@45, 400@0, 400@90, 200@180) the question is whether the 45°/90°/180° outputs rise *after* their
// 0° siblings (a lag) or *before* (a lead).
`timescale 1ps/1ps

module ClockPhase;

  reg clk_in1 = 1'b0;
  reg reset   = 1'b1;
  wire locked;
  wire clk_out1, clk_out2, clk_out3, clk_out4, clk_out5, clk_out6;

  clk_wiz_0 dut (
      .clk_in1 (clk_in1),
      .reset   (reset),
      .locked  (locked),
      .clk_out1(clk_out1),
      .clk_out2(clk_out2),
      .clk_out3(clk_out3),
      .clk_out4(clk_out4),
      .clk_out5(clk_out5),
      .clk_out6(clk_out6)
  );

  always #10000 clk_in1 = ~clk_in1;  // the board's 50 MHz oscillator

  time t0, t1a, t1b, t2a, t2b, t3a, t3b, t4a, t4b, t5a, t5b, t6a, t6b;

  // One measurement: a reference edge, then every output's first rising edge after it (all waits start together) and
  // the edge after that, so each output's period shows beside its phase.
  task automatic run;
    @(posedge clk_in1);
    t0 = $time;
    fork
      begin @(posedge clk_out1); t1a = $time; @(posedge clk_out1); t1b = $time; end
      begin @(posedge clk_out2); t2a = $time; @(posedge clk_out2); t2b = $time; end
      begin @(posedge clk_out3); t3a = $time; @(posedge clk_out3); t3b = $time; end
      begin @(posedge clk_out4); t4a = $time; @(posedge clk_out4); t4b = $time; end
      begin @(posedge clk_out5); t5a = $time; @(posedge clk_out5); t5b = $time; end
      begin @(posedge clk_out6); t6a = $time; @(posedge clk_out6); t6b = $time; end
    join
  endtask

  integer i;
  initial begin
    #2000000 reset = 1'b0;
    wait (locked);
    for (i = 0; i < 3; i = i + 1) begin
      run;
      $display("ClockPhase: phase/period(ps) o1=%0d/%0d o2=%0d/%0d o3=%0d/%0d o4=%0d/%0d o5=%0d/%0d o6=%0d/%0d",
               t1a - t0, t1b - t1a, t2a - t0, t2b - t2a, t3a - t0, t3b - t3a,
               t4a - t0, t4b - t4a, t5a - t0, t5b - t5a, t6a - t0, t6b - t6a);
    end
    $display("ClockPhase: PASS");
    $finish;
  end

endmodule
