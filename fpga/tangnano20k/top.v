// Tang Nano 20K — LED demo + UART TX (hello_world every 1s)
// 100 MHz clock (pin 13), 100000 baud
// UART: rx=69, tx=70

module top(
    input  wire       clk,        // pin 13, 100 MHz
    input  wire       s1,         // user key S1
    input  wire       s2,         // user key S2
    input  wire       uart_rx,    // pin 69 (unused)
    output wire       uart_tx,    // pin 70
    output wire [5:0] led         // active low
);

    // ── LED ────────────────────────────────────────────────────
    localparam HALF_SEC = 26'd49_999_999;
    reg [25:0] led_cnt = 0;
    reg [3:0]  pattern = 4'b1110;

    always @(posedge clk) begin
        if (led_cnt == HALF_SEC) begin
            led_cnt  <= 0;
            pattern <= {pattern[2:0], pattern[3]};
        end else
            led_cnt <= led_cnt + 1;
    end
    assign led[3:0] = pattern;
    assign led[4]   = ~s1;
    assign led[5]   = ~s2;

    // ── UART TX ────────────────────────────────────────────────
    // 100000 baud @ 100 MHz → 1000 cycles/bit
    localparam BAUD_DIV = 1000;

    reg [15:0] baud_cnt = 0;
    always @(posedge clk) begin
        if (baud_cnt == BAUD_DIV - 1)
            baud_cnt <= 0;
        else
            baud_cnt <= baud_cnt + 1;
    end
    wire baud_tick = (baud_cnt == BAUD_DIV - 1);

    // message ROM
    reg [7:0] msg [0:11];
    initial begin
        msg[0]  = "h";  msg[1]  = "e";  msg[2]  = "l";  msg[3]  = "l";
        msg[4]  = "o";  msg[5]  = "_";  msg[6]  = "w";  msg[7]  = "o";
        msg[8]  = "r";  msg[9]  = "l";  msg[10] = "d";  msg[11] = "\n";
    end

    // state
    reg [26:0] wait_cnt = 0;
    localparam WAIT_TOP = 27'd99_999_999;

    reg [3:0]  char_idx = 0;
    reg [3:0]  bit_idx  = 0;
    reg [9:0]  tx_shift = 10'b1111111111;
    reg        tx_busy  = 0;

    always @(posedge clk) begin
        if (!tx_busy) begin
            if (wait_cnt == WAIT_TOP) begin
                wait_cnt <= 0;
                tx_busy  <= 1;
                char_idx <= 0;
                tx_shift <= {1'b1, msg[0], 1'b0};  // start=0, data, stop=1
                bit_idx  <= 0;
            end else begin
                wait_cnt <= wait_cnt + 1;
            end
        end else if (baud_tick) begin
            if (bit_idx == 10) begin
                // byte done, load next or finish
                if (char_idx == 11) begin
                    tx_busy  <= 0;
                    tx_shift <= 10'b1111111111;
                end else begin
                    char_idx <= char_idx + 1;
                    bit_idx  <= 0;
                    tx_shift <= {1'b1, msg[char_idx + 1], 1'b0};
                end
            end else begin
                tx_shift <= {1'b1, tx_shift[9:1]};
                bit_idx  <= bit_idx + 1;
            end
        end
    end

    assign uart_tx = tx_shift[0];

endmodule