// 4-state simulation testbench — UART boot via BootFsm protocol.
`timescale 1ns / 1ps
module tb;
    reg clk, rst_n;
    wire commit_msg_valid, commit_msg_is_load;
    wire [31:0] commit_msg_next_pc, commit_msg_rd_value, commit_msg_mem_count;
    wire [4:0]  commit_msg_rd_index;
    wire [2:0]  commit_msg_csr_type;
    wire [31:0] commit_msg_csr_data;
    wire uart_txd, uart_rtsn;
    reg  uart_rxd, uart_ctsn;
    wire finish_passed;
    wire [31:0] uart_recv_count;
    wire [1:0]  tracker_result;

    localparam RESET_CYCLES = 10;
    localparam BAUD = 100_000_000 / 115200;  // ~868 cycles/bit (matches DUT UartTx)

    initial clk = 0; always #5 clk = ~clk;
    NzeaTile tile (.clock(clk), .reset(~rst_n),
        .io_commit_msg_valid(commit_msg_valid),
        .io_commit_msg_bits_next_pc(commit_msg_next_pc),
        .io_commit_msg_bits_rd_index(commit_msg_rd_index),
        .io_commit_msg_bits_rd_value(commit_msg_rd_value),
        .io_commit_msg_bits_mem_count(commit_msg_mem_count),
        .io_commit_msg_bits_is_load(commit_msg_is_load),
        .io_commit_msg_bits_csr_type(commit_msg_csr_type),
        .io_commit_msg_bits_csr_data(commit_msg_csr_data),
        .io_fpga_uart_txd(uart_txd), .io_fpga_uart_rxd(uart_rxd),
        .io_fpga_finish(finish_passed));

    assign uart_ctsn=1'b0;
    reg uart_tx=1'b1; assign uart_rxd=uart_tx;

    // ---- Sub-modules (Chisel-generated) ----
    UartRxDisplay uart_mon(.clock(clk), .reset(~rst_n), .io_rxd(uart_txd), .io_recvCount(uart_recv_count));

    CommitTracker tracker (
        .clock(clk), .reset(~rst_n),
        .io_commitMsgValid(commit_msg_valid),
        .io_commitMsgNextPC(commit_msg_next_pc),
        .io_commitMsgRdIndex(commit_msg_rd_index),
        .io_commitMsgRdValue(commit_msg_rd_value),
        .io_finishPassed(finish_passed),
        .io_result(tracker_result)
    );

    // ---- Commit display + stop logic ----
    reg [31:0] sim_cycle;
    reg [31:0] commit_cnt;
    reg [31:0] stored_pc;
    reg        finisher_seen;
    reg [15:0] drain_cnt;
    reg        wave_enabled;
    localparam DRAIN_MAX = 50000;
    // Waveform path hint
    task show_wave;
        if (wave_enabled)
            $display("Waveform: build/sim/tile/%s/%s/hw/iverilog/tb.fst", `PLATFORM, `ISA);
    endtask
    // UART monitor regs (declared here, used below)
    reg        uart_active;
    reg [15:0] uart_sample_cnt;
    reg [3:0]  uart_bit;
    reg [7:0]  uart_char;
    reg        uart_txd_d1;
    always @(posedge clk) begin
        // CommitTracker FAIL (timeout) → stop immediately
        if (tracker_result == 2'b10) begin
            show_wave;
            $display("[%0t] RESULT: FAIL (%0d commits)", $time, commit_cnt);
            $finish;
        end
        sim_cycle <= sim_cycle + 1;
        if (sim_cycle > 50000000) begin
            show_wave;
            $display("[%0t] TIMEOUT (fallback, %0d commits)", $time, commit_cnt);
            $finish;
        end
        // Finisher: stop logging commits, wait for UART drain
        if (finish_passed && !finisher_seen) begin
            $display("[%0t] Finisher triggered", $time);
            finisher_seen <= 1;
            drain_cnt <= 0;
        end
        if (finisher_seen) begin
            drain_cnt <= drain_cnt + 1;
            // UART idle for 2 char-times (2 * 10 bits * BAUD)
            if (!uart_active && drain_cnt > (BAUD * 20)) begin
                show_wave;
                $display("[%0t] RESULT: PASS (%0d commits)", $time, commit_cnt);
                $finish;
            end
            if (drain_cnt > DRAIN_MAX) begin
                show_wave;
                $display("[%0t] RESULT: PASS (drain timeout, %0d commits)", $time, commit_cnt);
                $finish;
            end
        end else if (commit_msg_valid) begin
            commit_cnt <= commit_cnt + 1;
            $display("[%0t] #%0d (c%0d): pc=%08h next_pc=%08h rd=x%0d val=%08h",
                $time, commit_cnt + 1, sim_cycle, stored_pc, commit_msg_next_pc,
                commit_msg_rd_index, commit_msg_rd_value);
            stored_pc <= commit_msg_next_pc;
        end
    end

    // ---- UART TX monitor (replaces UartRxDisplay printf lost to CIRCT) ----
    always @(posedge clk) begin
        uart_txd_d1 <= uart_txd;
        if (!uart_active && uart_txd_d1 && !uart_txd) begin  // start bit
            uart_active <= 1;
            uart_bit <= 0;
            uart_sample_cnt <= (BAUD / 2) - 1;
        end
        if (uart_active) begin
            uart_sample_cnt <= uart_sample_cnt - 1;
            if (uart_sample_cnt == 0) begin
                uart_sample_cnt <= BAUD - 1;
                if (uart_bit > 0 && uart_bit < 9)
                    uart_char[uart_bit - 1] <= uart_txd;
                if (uart_bit == 9) begin
                    uart_active <= 0;
                    $display("[%0t] UART_TX: %02h (%c)", $time, uart_char,
                        (uart_char >= 32 && uart_char < 127) ? uart_char : ".");
                end
                uart_bit <= uart_bit + 1;
            end
        end
    end

    // ---- UART TX tasks (boot protocol) ----
    `include "nzea_sim/sim/boot/uart_tasks.svh"

    // ---- UART boot protocol ----
    `include "nzea_sim/sim/boot/uart_boot.svh"

    // ---- Test program ----
    reg [1023:0] hex_file;
    reg [1023:0] boot_mode;
    reg [1023:0] wave_mode;
    `ifndef HEX_BUF_WORDS
    `define HEX_BUF_WORDS 256
    `endif
    reg [31:0] boot_buf [0:`HEX_BUF_WORDS-1];
    integer boot_bi, hex_size;
    reg [1023:0] dump_wave;

    initial begin
        if ($value$plusargs("WAVE=%s", dump_wave)) begin
            if (dump_wave == "1") begin
                $dumpfile("tb.fst"); $dumpvars(0, tb);
                wave_enabled = 1;
            end else if (dump_wave != "0") begin
                $display("ERROR: +WAVE= must be 0 or 1, got '%s'", dump_wave);
                $finish;
            end
        end
        // Init RAM with harmless infinite loop (jal x0, 0)
        begin integer ri;
            for(ri=0; ri<32768; ri=ri+1) begin
                tb.tile.ram.memBytes_0_ext.Memory[ri] = 8'h6F;
                tb.tile.ram.memBytes_1_ext.Memory[ri] = 8'h00;
                tb.tile.ram.memBytes_2_ext.Memory[ri] = 8'h00;
                tb.tile.ram.memBytes_3_ext.Memory[ri] = 8'h00;
            end
        end
        if (!$value$plusargs("HEX=%s", hex_file)) hex_file = "hello.hex";
        if (!$value$plusargs("BOOT=%s", boot_mode)) boot_mode = "dir";
        if (boot_mode != "dir" && boot_mode != "uart") begin
            $display("ERROR: +BOOT= must be 'dir' or 'uart', got '%s'", boot_mode);
            $finish;
        end
        // Load hex before reset
        if (boot_mode == "dir") begin
            `include "nzea_sim/sim/boot/direct_boot.svh"
            // Prevent BootFsm from overwriting the direct-loaded hex
            force tb.tile.bootFsm.io_ram_wen = 1'b0;
        end
        commit_cnt = 0;
        stored_pc = 0;
        sim_cycle = 0;
        finisher_seen = 0;
        drain_cnt = 0;
        uart_active = 0;
        uart_sample_cnt = 0;
        uart_bit = 0;
        uart_char = 0;
        uart_txd_d1 = 1;
        rst_n=0; repeat(RESET_CYCLES) @(posedge clk);
        rst_n=1; uart_tx<=1'b1; repeat(200) @(posedge clk);
        if (boot_mode != "dir") begin
            boot_from_hex(hex_file);
        end
    end
endmodule
