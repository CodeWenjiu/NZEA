// 4-state simulation testbench — UART boot via BootFsm protocol.
`timescale 1ns / 1ps
module tb;
    reg clk, rst_n;
    wire commit_msg_valid, commit_msg_is_load;
    wire [31:0] commit_msg_next_pc, commit_msg_rd_value, commit_msg_mem_count;
    wire [4:0]  commit_msg_rd_index;
    wire [2:0]  commit_msg_csr_type;
    wire [31:0] commit_msg_csr_data;
    wire uart_txd, uart_rtsn, uart_interrupt;
    reg  uart_rxd, uart_ctsn, boot_override;
    wire finish_passed;
    wire cpu_running = !tb.dut.tile.core.reset;

    localparam RESET_CYCLES = 10;
    localparam BAUD = 100_000_000 / 1000000;  // 100 cycles/bit

    initial clk = 0; always #5 clk = ~clk;
    Top dut (.clock(clk), .reset(~rst_n),
        .commit_msg_valid(commit_msg_valid),
        .commit_msg_bits_next_pc(commit_msg_next_pc),
        .commit_msg_bits_rd_index(commit_msg_rd_index),
        .commit_msg_bits_rd_value(commit_msg_rd_value),
        .commit_msg_bits_mem_count(commit_msg_mem_count),
        .commit_msg_bits_is_load(commit_msg_is_load),
        .commit_msg_bits_csr_type(commit_msg_csr_type),
        .commit_msg_bits_csr_data(commit_msg_csr_data),
        .uart_txd(uart_txd), .uart_rxd(uart_rxd),
        .uart_rtsn(uart_rtsn), .uart_ctsn(uart_ctsn),
        .uart_interrupt(uart_interrupt),
        .fpga_finish(finish_passed),
        .boot_override(boot_override));

    assign uart_ctsn=1'b0;
    reg uart_tx=1'b1; assign uart_rxd=uart_tx;

    // ---- Sub-modules ----
    uart_tx_monitor uart_mon(.clk(clk), .uart_txd(uart_txd));

    commit_tracker #(
        .MAX_CYCLES(50000000), .FINISH_DRAIN(20000),
        .START_PC(32'h80000000), .POST_FINISHER_COMMITS(15)
    ) tracker (
        .clk(clk), .rst_n(rst_n), .cpu_running(cpu_running),
        .commit_msg_valid(commit_msg_valid),
        .commit_msg_next_pc(commit_msg_next_pc),
        .commit_msg_rd_index(commit_msg_rd_index),
        .commit_msg_rd_value(commit_msg_rd_value),
        .finish_passed(finish_passed)
    );

    // ---- UART TX tasks (boot protocol) ----
    `include "iverilog_tb/boot/uart_tasks.svh"

    // ---- UART boot protocol ----
    `include "iverilog_tb/boot/uart_boot.svh"

    // ---- Test program ----
    reg [1023:0] hex_file;
    reg [1023:0] boot_mode;
    reg [1023:0] wave_mode;
    reg [31:0] boot_buf [0:32767];
    integer boot_bi, hex_size;
    reg [1023:0] dump_wave;

    initial begin
        if ($value$plusargs("WAVE=%s", dump_wave)) begin
            if (dump_wave == "1") begin
                $dumpfile("tb.fst"); $dumpvars(0, tb);
            end else if (dump_wave != "0") begin
                $display("ERROR: +WAVE= must be 0 or 1, got '%s'", dump_wave);
                $finish;
            end
        end
        // Init PHT/BTB
        begin integer i; for(i=0;i<64;i=i+1) tb.dut.tile.core.ifu.pht.mem_ext.Memory[i]=2'b01;
            for(i=0;i<16;i=i+1) tb.dut.tile.core.ifu.btb.mem_ext.Memory[i]='0; end
        if (!$value$plusargs("HEX=%s", hex_file)) hex_file = "hello.hex";
        if (!$value$plusargs("BOOT=%s", boot_mode)) boot_mode = "dir";
        if (boot_mode != "dir" && boot_mode != "uart") begin
            $display("ERROR: +BOOT= must be 'dir' or 'uart', got '%s'", boot_mode);
            $finish;
        end
        boot_override = (boot_mode != "dir");
        // Load RAM before releasing reset (for direct mode)
        if (boot_mode == "dir") begin
            `include "iverilog_tb/boot/direct_boot.svh"
        end
        rst_n=0; repeat(RESET_CYCLES) @(posedge clk);
        rst_n=1; uart_tx<=1'b1; repeat(200) @(posedge clk);
        if (boot_mode != "dir") begin
            boot_from_hex(hex_file);
        end
    end
endmodule
