// A7-Lite — NzeaTile SoC top (hellofpga platform, 50MHz)
module top(
    input  wire       CLK_50M,    // pin J19, 50 MHz
    input  wire       RESET,      // pin L18, active-low
    output wire       UART_TX,    // pin V2 → PC RX
    input  wire       UART_RX,    // pin U2 ← PC TX
    output wire       LED1,       // pin M18 — finish indicator
    output wire       LED2        // pin N18 — commit activity
);

    // ── Reset synchronizer (board RESET is active-low) ─────────
    // RESET=0 按下复位, RESET=1 松手运行 → 给 tile 高有效复位
    reg reset_s1 = 1;
    reg reset_s2 = 1;
    always @(posedge CLK_50M) begin
        reset_s1 <= RESET;
        reset_s2 <= reset_s1;
    end
    wire rst = ~reset_s2;  // high when RESET is low

    // ── NzeaTile ───────────────────────────────────────────────
    wire        tile_finish;
    wire        tile_uart_txd;
    wire        tile_commit_valid;
    wire [31:0] commit_pc;
    wire [4:0]  commit_rd;
    wire [31:0] commit_val;

    NzeaTile tile (
        .clock  (CLK_50M),
        .reset  (rst),
        .io_fpga_uart_txd              (tile_uart_txd),
        .io_fpga_uart_rxd              (UART_RX),
        .io_fpga_finish                (tile_finish),
        .io_commit_msg_valid           (tile_commit_valid),
        .io_commit_msg_bits_next_pc    (commit_pc),
        .io_commit_msg_bits_rd_index   (commit_rd),
        .io_commit_msg_bits_rd_value   (commit_val),
        .io_commit_msg_bits_mem_count  (),
        .io_commit_msg_bits_is_load    (),
        .io_commit_msg_bits_csr_type   (),
        .io_commit_msg_bits_csr_data   ()
    );

    // ── Commit activity LED (stretch 1-cycle pulse to ~0.1s) ───
    localparam STRETCH = 26'd4_999_999;  // 50M / 10 = 5M = 0.1s
    reg [24:0] stretch_cnt = 0;
    reg        commit_led  = 0;

    always @(posedge CLK_50M) begin
        if (rst) begin
            stretch_cnt <= 0;
            commit_led  <= 0;
        end else begin
            if (tile_commit_valid)
                stretch_cnt <= STRETCH;
            else if (stretch_cnt > 0)
                stretch_cnt <= stretch_cnt - 1;

            commit_led <= (stretch_cnt > 0);
        end
    end

    // ── IO ─────────────────────────────────────────────────────
    assign UART_TX = tile_uart_txd;
    assign LED1    = tile_finish;
    assign LED2    = commit_led;

endmodule