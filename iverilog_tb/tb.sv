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

    localparam MAX_CYCLES = 40000, RESET_CYCLES = 10;
    integer cycle, commit_count;
    reg xz_reported, commit_warned;

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
        .boot_override(boot_override));

    assign uart_ctsn=1'b0; assign boot_override=1'b1;
    reg uart_tx=1'b1; assign uart_rxd=uart_tx;

    // ---- UART TX monitor (1Mbps 8N1) ----
    integer uart_fd;
    reg  [7:0]  uart_char;
    reg  [15:0] uart_sample_cnt;
    reg  [3:0]  uart_bit;
    reg         uart_active;
    reg         uart_done;
    reg         uart_txd_d1;

    localparam BAUD = 100_000_000 / 1000000;   // 100 cycles/bit
    localparam HALF = BAUD / 2;                 // 50

    initial begin uart_fd = $fopen("uart_output.txt", "w"); uart_active=0; uart_done=0; end

    always @(posedge clk) begin
        uart_txd_d1 <= uart_txd;
        if (!uart_active && uart_txd_d1 && !uart_txd) begin
            uart_active      <= 1;
            uart_bit         <= 0;
            uart_sample_cnt  <= HALF - 1;  // mid-start-bit → skip
        end
        if (uart_active) begin
            uart_sample_cnt <= uart_sample_cnt - 1;
            if (uart_sample_cnt == 0) begin
                uart_sample_cnt <= BAUD - 1;  // next bit in 100 cycles
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
            $display("[%0t] UART_TX_MON: char=%02h (%c)", $time, uart_char, (uart_char >= 32 && uart_char < 127) ? uart_char : ".");
            if (uart_char != 0) $fwrite(uart_fd, "%c", uart_char);
        end
    end
    final $fclose(uart_fd);

    // ---- UART TX task (sends boot protocol) ----

    task uart_send_byte; input [7:0] d; reg [9:0] f; integer i; begin
        f={1'b1, d[7:0], 1'b0}; for(i=0;i<10;i=i+1) begin uart_tx<=f[0]; f={1'b1,f[9:1]}; repeat(BAUD) @(posedge clk); end end endtask
    task uart_send_word; input [31:0] w; begin
        uart_send_byte(w[31:24]); uart_send_byte(w[23:16]); uart_send_byte(w[15:8]); uart_send_byte(w[7:0]); end endtask

    initial begin
        cycle=0; commit_count=0; xz_reported=0; commit_warned=0;
        $dumpfile("tb.fst"); $dumpvars(0, tb); $dumplimit(0);
        // Init PHT/BTB
        begin integer i; for(i=0;i<64;i=i+1) tb.dut.tile.core.ifu.pht.mem_ext.Memory[i]=2'b01;
            for(i=0;i<16;i=i+1) tb.dut.tile.core.ifu.btb.mem_ext.Memory[i]='0; end
        rst_n=0; repeat(RESET_CYCLES) @(posedge clk);
        rst_n=1; uart_tx<=1'b1; repeat(200) @(posedge clk);
        // BootFsm protocol via UART (shift reg stores last byte at MSB → reversed words)
        uart_send_word(32'h07B007B0);  // → 0xB007B007
        uart_send_word(32'h00000000);  // address
        uart_send_word(32'h04000000);  // size=4 words
        uart_send_word(32'hB7020010);  // 0x100002B7  lui t0,0x10000
        uart_send_word(32'h13038004);  // 0x04800313  li t1,'H'
        uart_send_word(32'h23A06200);  // 0x0062A023  sw t1,0(t0)
        uart_send_word(32'h6F000000);  // 0x0000006F  j loop
        // Wait for BootFsm to finish (state == sDone = 5)
        while(tb.dut.tile._bootFsm_io_cpu_reset !== 1'b0) @(posedge clk);
        cycle=0;
        while(cycle<MAX_CYCLES) begin @(posedge clk); cycle=cycle+1;
            if(commit_msg_valid) begin commit_count=commit_count+1;
                $display("[%0t] #%0d (c%0d): pc=%08h rd=x%0d val=%08h",
                    $time, commit_count, cycle, commit_msg_next_pc, commit_msg_rd_index, commit_msg_rd_value); end end
        if(commit_count==0) $display("FAIL: no commits");
        else $display("PASS: %0d commits", commit_count);
        $finish;
    end
endmodule
