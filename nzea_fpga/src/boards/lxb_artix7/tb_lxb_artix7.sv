`timescale 1ns / 1ps

module tb_lxb_artix7;

  reg        CLK_50M  = 0;
  reg        RESET    = 1;
  wire       UART_TX;
  wire       UART_RX = 1;
  wire       LED1;
  wire       LED2;

  LxbArtix7Top dut (
    .CLK_50M (CLK_50M),
    .RESET   (RESET),
    .UART_TX (UART_TX),
    .UART_RX (UART_RX),
    .LED1    (LED1),
    .LED2    (LED2)
  );

  always #5 CLK_50M = ~CLK_50M;

  integer cycle;

  initial begin
    $display("[%0t] === Start ===", $time);

    // Print first few cycles to confirm reset
    repeat(10) begin
      @(posedge CLK_50M);
      $display("[%0t] RESET=%b LED1=%b LED2=%b", $time, RESET, LED1, LED2);
    end

    RESET = 0;  repeat(100) @(posedge CLK_50M);
    RESET = 1;
    $display("[%0t] Reset released", $time);

    // Run long enough for LED blink (~1.3s period → need 100M+ cycles)
    cycle = 0;
    while (cycle < 200000000) begin
      @(posedge CLK_50M);
      cycle = cycle + 1;
      if (cycle % 20000000 == 0)
        $display("[%0t] c=%0d LED1=%b LED2=%b", $time, cycle, LED1, LED2);
    end
    $display("[%0t] === Timeout ===", $time);
  end

endmodule
