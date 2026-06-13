`timescale 1ns / 1ps

module tb_lxb_artix7;

  reg        CLK_50M  = 0;
  reg        RESET    = 1;   // active-low, 1 = not pressed
  wire       UART_TX;
  wire       UART_RX = 1;    // idle high
  wire       LED1;
  wire       LED2;

  // ── DUT ────────────────────────────────────────────────────
  LxbArtix7Top dut (
    .CLK_50M (CLK_50M),
    .RESET   (RESET),
    .UART_TX (UART_TX),
    .UART_RX (UART_RX),
    .LED1    (LED1),
    .LED2    (LED2)
  );

  // ── Clock ──────────────────────────────────────────────────
  always #5 CLK_50M = ~CLK_50M;

  // ── Stimulus ───────────────────────────────────────────────
  integer cycle;

  initial begin
    $display("[%0t] === Start ===", $time);
    RESET = 0;  repeat(100) @(posedge CLK_50M);
    RESET = 1;
    $display("[%0t] Reset released", $time);

    cycle = 0;
    while (cycle < 10000000) begin
      @(posedge CLK_50M);
      cycle = cycle + 1;
      if (cycle % 1000000 == 0)
        $display("[%0t] c=%0d LED1=%b LED2=%b UART_TX=%b", $time, cycle, LED1, LED2, UART_TX);
    end
    $display("[%0t] === Timeout ===", $time);
  end

endmodule
