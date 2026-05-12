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
    wire cpu_running = !tb.dut.tile._bootFsm_io_cpu_reset;

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

    assign uart_ctsn=1'b0; assign boot_override=1'b1;
    reg uart_tx=1'b1; assign uart_rxd=uart_tx;

    // ---- Sub-modules ----
    uart_tx_monitor uart_mon(.clk(clk), .uart_txd(uart_txd));

    commit_tracker #(
        .MAX_CYCLES(40000), .FINISH_DRAIN(20000),
        .START_PC(32'h80000000), .POST_FINISHER_COMMITS(15)
    ) tracker (
        .clk(clk), .rst_n(rst_n), .cpu_running(cpu_running),
        .commit_msg_valid(commit_msg_valid),
        .commit_msg_next_pc(commit_msg_next_pc),
        .commit_msg_rd_index(commit_msg_rd_index),
        .commit_msg_rd_value(commit_msg_rd_value),
        .finish_passed(finish_passed)
    );

    // ---- UART TX task (sends boot protocol) ----
    task uart_send_byte; input [7:0] d; reg [9:0] f; integer i; begin
        f={1'b1, d[7:0], 1'b0};
        for(i=0;i<10;i=i+1) begin uart_tx<=f[0]; f={1'b1,f[9:1]}; repeat(BAUD) @(posedge clk); end
    end endtask
    task uart_send_word; input [31:0] w; begin
        uart_send_byte(w[31:24]); uart_send_byte(w[23:16]);
        uart_send_byte(w[15:8]);  uart_send_byte(w[7:0]);
    end endtask

    // ---- Test program (Hello_World via UART boot) ----
    initial begin
        $dumpfile("tb.fst"); $dumpvars(0, tb); $dumplimit(0);
        // Init PHT/BTB
        begin integer i; for(i=0;i<64;i=i+1) tb.dut.tile.core.ifu.pht.mem_ext.Memory[i]=2'b01;
            for(i=0;i<16;i=i+1) tb.dut.tile.core.ifu.btb.mem_ext.Memory[i]='0; end
        rst_n=0; repeat(RESET_CYCLES) @(posedge clk);
        rst_n=1; uart_tx<=1'b1; repeat(200) @(posedge clk);
        // BootFsm protocol via UART (shift reg stores last byte at MSB → reversed words)
        uart_send_word(32'h07B007B0);  // → 0xB007B007 magic
        uart_send_word(32'h00000000);  // address = 0
        uart_send_word(32'h1A000000);  // size = 26 words
        // Hello_World: inline writes (UART now has TX hold buffer)
        uart_send_word(32'hB7020010);  // 0x100002B7  lui t0,0x10000       # UART base
        uart_send_word(32'h13038004);  // 0x04800313  addi t1,zero,0x48    'H'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h13035006);  // 0x06500313  addi t1,zero,0x65    'e'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303C006);  // 0x06C00313  addi t1,zero,0x6C    'l'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303C006);  // 0x06C00313  addi t1,zero,0x6C    'l'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303F006);  // 0x06F00313  addi t1,zero,0x6F    'o'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303F005);  // 0x05F00313  addi t1,zero,0x5F    '_'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h13037005);  // 0x05700313  addi t1,zero,0x57    'W'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303F006);  // 0x06F00313  addi t1,zero,0x6F    'o'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h13032007);  // 0x07200313  addi t1,zero,0x72    'r'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h1303C006);  // 0x06C00313  addi t1,zero,0x6C    'l'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h13034006);  // 0x06400313  addi t1,zero,0x64    'd'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'hB7031000);  // 0x001003B7  lui t2,0x00100       # finisher base
        uart_send_word(32'h23A00300);  // 0x0003A023  sw zero,0(t2)        → finisher
        uart_send_word(32'h6F000000);  // 0x0000006F  j loop
        // Test completion handled by commit_tracker
    end
endmodule
