// A7-Lite — LED demo + UART TX (hello_world every 1s)
// 50 MHz clock, 115200 baud
// LED1/2 are active high (set_property not specifying inversion, so 1=on)

module top(
    input  wire       CLK_50M,    // pin J19, 50 MHz
    input  wire       RESET,      // pin L18, active high
    input  wire       KEY1,       // pin AA1 (pressed = low)
    input  wire       KEY2,       // pin W1
    input  wire       UART_RX,    // pin U2 (unused)
    output wire       UART_TX,    // pin V2
    output wire       LED1,       // pin M18
    output wire       LED2        // pin N18
);

    // ── Reset synchronizer ─────────────────────────────────────
    reg reset_r1 = 1;
    reg reset_r2 = 1;
    always @(posedge CLK_50M) begin
        reset_r1 <= RESET;
        reset_r2 <= reset_r1;
    end
    wire rst = reset_r2;

    // ── LED: running light on LED1, key mirror on LED2 ─────────
    localparam HALF_SEC = 26'd24_999_999;  // 50M / 2
    reg [25:0] led_cnt = 0;
    reg        led_pattern = 0;

    always @(posedge CLK_50M) begin
        if (rst) begin
            led_cnt     <= 0;
            led_pattern <= 0;
        end else if (led_cnt == HALF_SEC) begin
            led_cnt     <= 0;
            led_pattern <= ~led_pattern;
        end else begin
            led_cnt <= led_cnt + 1;
        end
    end
    assign LED1 = led_pattern;
    assign LED2 = ~KEY2;  // pressed → high → LED on

    // ── UART TX ────────────────────────────────────────────────
    localparam BAUD_DIV = 434;  // 50M / 115200 ≈ 434

    reg [15:0] baud_cnt = 0;
    always @(posedge CLK_50M) begin
        if (baud_cnt == BAUD_DIV - 1)
            baud_cnt <= 0;
        else
            baud_cnt <= baud_cnt + 1;
    end
    wire baud_tick = (baud_cnt == BAUD_DIV - 1);

    reg [7:0] msg [0:11];
    initial begin
        msg[0]  = "h";  msg[1]  = "e";  msg[2]  = "l";  msg[3]  = "l";
        msg[4]  = "o";  msg[5]  = "_";  msg[6]  = "w";  msg[7]  = "o";
        msg[8]  = "r";  msg[9]  = "l";  msg[10] = "d";  msg[11] = "\n";
    end

    reg [25:0] wait_cnt = 0;
    localparam WAIT_TOP = 26'd49_999_999;  // 1s

    reg [3:0]  char_idx = 0;
    reg [3:0]  bit_idx  = 0;
    reg [9:0]  tx_shift = 10'b1111111111;
    reg        tx_busy  = 0;

    always @(posedge CLK_50M) begin
        if (rst) begin
            wait_cnt  <= 0;
            tx_busy   <= 0;
            tx_shift  <= 10'b1111111111;
        end else if (!tx_busy) begin
            if (wait_cnt == WAIT_TOP) begin
                wait_cnt  <= 0;
                tx_busy   <= 1;
                char_idx  <= 0;
                tx_shift  <= {1'b1, msg[0], 1'b0};
                bit_idx   <= 0;
            end else begin
                wait_cnt <= wait_cnt + 1;
            end
        end else if (baud_tick) begin
            if (bit_idx == 10) begin
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

    assign UART_TX = tx_shift[0];

endmodule