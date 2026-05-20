// UART TX monitor — samples DUT's txd line and displays received characters.
// BAUD = 100MHz / 1Mbps = 100 cycles/bit.
module uart_tx_monitor(input clk, input uart_txd);
    integer uart_fd;
    reg  [7:0]  uart_char;
    reg  [15:0] uart_sample_cnt;
    reg  [3:0]  uart_bit;
    reg         uart_active;
    reg         uart_done;
    reg         uart_txd_d1;

    localparam BAUD_RATE = 100_000_000 / 115200;   // ~868 cycles/bit
    localparam HALF = BAUD_RATE / 2;                 // 50

    initial begin
        uart_fd    = $fopen("uart_output.txt", "w");
        uart_active = 0;
        uart_done   = 0;
    end

    always @(posedge clk) begin
        uart_txd_d1 <= uart_txd;
        if (!uart_active && uart_txd_d1 && !uart_txd) begin
            uart_active      <= 1;
            uart_bit         <= 0;
            uart_sample_cnt  <= HALF - 1;
        end
        if (uart_active) begin
            uart_sample_cnt <= uart_sample_cnt - 1;
            if (uart_sample_cnt == 0) begin
                uart_sample_cnt <= BAUD_RATE - 1;
                if (uart_bit == 0) begin
                    ;  // start bit — skip
                end else if (uart_bit < 9) begin
                    uart_char[uart_bit - 1] <= uart_txd;
                end
                if (uart_bit == 9) begin
                    uart_active <= 0;
                    uart_done   <= 1;
                end
                uart_bit <= uart_bit + 1;
            end
        end
        if (uart_done) begin
            uart_done <= 0;
            $display("[%0t] UART_TX_MON: char=%02h (%c)", $time, uart_char,
                (uart_char >= 32 && uart_char < 127) ? uart_char : ".");
            if (uart_char != 0) $fwrite(uart_fd, "%c", uart_char);
        end
    end

    final $fclose(uart_fd);
endmodule
