// Toolchain spike testbench: drives the generated Poc_w8_d4 and checks the local
// register path, the instantiated sub-generator and the external counter module.
`timescale 1ns/1ps

module tb;
  logic clock = 0;
  logic resetN = 0;
  logic [7:0] in = 8'd3;
  logic sel = 0;
  logic [7:0] sum;
  logic [31:0] count;

  Poc_w8_d4 dut (
    .clock  (clock),
    .resetN (resetN),
    .in     (in),
    .sel    (sel),
    .sum    (sum),
    .count  (count)
  );

  always #5 clock = ~clock;

  int errors = 0;

  task automatic check(input string label, input logic [31:0] got, input logic [31:0] want);
    if (got !== want) begin
      $display("FAIL %s: got %0d want %0d", label, got, want);
      errors++;
    end else begin
      $display("ok   %s = %0d", label, got);
    end
  endtask

  initial begin
    logic [7:0] sumPrev;

    repeat (3) @(posedge clock);
    resetN = 1;

    // Register path: the accumulator advances by exactly one per cycle.
    @(posedge clock);
    #1;
    sumPrev = sum;
    @(posedge clock);
    #1;
    check("sum_increments_by_one", sum, sumPrev + 8'd1);

    // Sub-generator path: sel loads in + in = 6 through the instantiated adder.
    sel = 1;
    @(posedge clock);
    #1;
    check("sum_after_sel", sum, 6);

    // External counter: the accumulator crossed `in` (3) once on the way up.
    check("count_after_first_crossing", count, 1);

    // ... and once more when it reaches the new `in` (9).
    sel = 0;
    in = 8'd9;
    repeat (4) @(posedge clock);
    #1;
    check("count_after_second_crossing", count, 2);

    $display("errors=%0d %s", errors, errors == 0 ? "POC PASS" : "POC FAIL");
    $finish;
  end
endmodule
