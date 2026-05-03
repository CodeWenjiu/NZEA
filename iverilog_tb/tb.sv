// 4-state simulation testbench for nzea Tile (--sim false).
// Known CPU RTL issue: commit_msg_valid may be X when no commit is active
// due to unreset registers in Commit/ROB.

`timescale 1ns / 1ps

module tb;
    reg clk;
    reg rst_n;

    // commit_msg
    wire        commit_msg_valid;
    wire [31:0] commit_msg_next_pc;
    wire [4:0]  commit_msg_rd_index;
    wire [31:0] commit_msg_rd_value;
    wire [31:0] commit_msg_mem_count;
    wire        commit_msg_is_load;
    wire [2:0]  commit_msg_csr_type;
    wire [31:0] commit_msg_csr_data;

    // UART pins
    wire        uart_txd;
    reg         uart_rxd;
    wire        uart_rtsn;
    reg         uart_ctsn;
    wire        uart_interrupt;

    // ---- Simulation control ----
    localparam MAX_CYCLES    = 500;
    localparam RESET_CYCLES  = 10;
    localparam SETTLE_CYCLES = 24;
    integer cycle;
    integer commit_count;
    reg     xz_reported;
    reg     commit_warned;

    // ---- Clock: 100MHz ----
    initial clk = 0;
    always #5 clk = ~clk;

    // ---- DUT ----
    Top dut (
        .clock                    (clk),
        .reset                    (~rst_n),
        .commit_msg_valid         (commit_msg_valid),
        .commit_msg_bits_next_pc  (commit_msg_next_pc),
        .commit_msg_bits_rd_index (commit_msg_rd_index),
        .commit_msg_bits_rd_value (commit_msg_rd_value),
        .commit_msg_bits_mem_count(commit_msg_mem_count),
        .commit_msg_bits_is_load  (commit_msg_is_load),
        .commit_msg_bits_csr_type (commit_msg_csr_type),
        .commit_msg_bits_csr_data (commit_msg_csr_data),
        .uart_txd                 (uart_txd),
        .uart_rxd                 (uart_rxd),
        .uart_rtsn                (uart_rtsn),
        .uart_ctsn                (uart_ctsn),
        .uart_interrupt           (uart_interrupt)
    );

    // UART loopback: TX → RX, CTS pulled low
    assign uart_rxd  = uart_txd;
    assign uart_ctsn = 1'b0;

    // ============================================================
    // X/Z check
    // ============================================================
    wire any_xz =
        (commit_msg_valid     === 1'bx) || (commit_msg_valid     === 1'bz) ||
        (uart_txd             === 1'bx) || (uart_txd             === 1'bz) ||
        (uart_rtsn            === 1'bx) || (uart_rtsn            === 1'bz) ||
        (uart_interrupt       === 1'bx) || (uart_interrupt       === 1'bz) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_next_pc  === 1'bx || ^commit_msg_next_pc  === 1'bz)) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_rd_index === 1'bx || ^commit_msg_rd_index === 1'bz)) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_rd_value === 1'bx || ^commit_msg_rd_value === 1'bz)) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_mem_count=== 1'bx || ^commit_msg_mem_count=== 1'bz)) ||
        (commit_msg_valid === 1'b1 && (commit_msg_is_load   === 1'bx || commit_msg_is_load   === 1'bz)) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_csr_type === 1'bx || ^commit_msg_csr_type === 1'bz)) ||
        (commit_msg_valid === 1'b1 && (^commit_msg_csr_data === 1'bx || ^commit_msg_csr_data === 1'bz));

    task report_xz;
        begin
            if (commit_msg_valid  === 1'bx) $display("  commit_msg_valid = X");
            if (commit_msg_valid  === 1'bz) $display("  commit_msg_valid = Z");
            if (uart_txd          === 1'bx) $display("  uart_txd      = X");
            if (uart_txd          === 1'bz) $display("  uart_txd      = Z");
            if (uart_rtsn         === 1'bx) $display("  uart_rtsn     = X");
            if (uart_rtsn         === 1'bz) $display("  uart_rtsn     = Z");
            if (uart_interrupt    === 1'bx) $display("  uart_interrupt= X");
            if (uart_interrupt    === 1'bz) $display("  uart_interrupt= Z");
            if (commit_msg_valid === 1'b1) begin
                if (^commit_msg_next_pc  === 1'bx) $display("  commit_msg_next_pc   = X (%h)", commit_msg_next_pc);
                if (^commit_msg_next_pc  === 1'bz) $display("  commit_msg_next_pc   = Z");
                if (^commit_msg_rd_index === 1'bx) $display("  commit_msg_rd_index  = X");
                if (^commit_msg_rd_index === 1'bz) $display("  commit_msg_rd_index  = Z");
                if (^commit_msg_rd_value === 1'bx) $display("  commit_msg_rd_value  = X");
                if (^commit_msg_rd_value === 1'bz) $display("  commit_msg_rd_value  = Z");
                if (^commit_msg_mem_count=== 1'bx) $display("  commit_msg_mem_count = X");
                if (^commit_msg_mem_count=== 1'bz) $display("  commit_msg_mem_count = Z");
                if (commit_msg_is_load   === 1'bx) $display("  commit_msg_is_load   = X");
                if (commit_msg_is_load   === 1'bz) $display("  commit_msg_is_load   = Z");
                if (^commit_msg_csr_type === 1'bx) $display("  commit_msg_csr_type  = X");
                if (^commit_msg_csr_type === 1'bz) $display("  commit_msg_csr_type  = Z");
                if (^commit_msg_csr_data === 1'bx) $display("  commit_msg_csr_data  = X");
                if (^commit_msg_csr_data === 1'bz) $display("  commit_msg_csr_data  = Z");
            end
        end
    endtask

    // ============================================================
    // Main simulation
    // ============================================================
    initial begin
        cycle         = 0;
        commit_count  = 0;
        xz_reported   = 0;
        commit_warned = 0;

        $dumpfile("tb.fst");
        $dumpvars(0, tb);
        $dumplimit(0);

        // Init PHT, BTB, and RAM with test program
        begin
            integer _mi;
            for (_mi = 0; _mi < 64; _mi = _mi + 1)
                tb.dut.tile.core.ifu.pht.mem_ext.Memory[_mi] = 2'b01;
            for (_mi = 0; _mi < 16; _mi = _mi + 1)
                tb.dut.tile.core.ifu.btb.mem_ext.Memory[_mi] = '0;

            // Test program (hello.hex) loaded into RAM at word 0 (PC = 0x8000_0000)
            tb.dut.tile.ram.mem_ext.Memory[0] = 32'h00A00093;
            tb.dut.tile.ram.mem_ext.Memory[1] = 32'h01400113;
            tb.dut.tile.ram.mem_ext.Memory[2] = 32'h002081B3;
            tb.dut.tile.ram.mem_ext.Memory[3] = 32'h40110233;
            tb.dut.tile.ram.mem_ext.Memory[4] = 32'h0000006F;

            $display("RAM init: [0]=%h [1]=%h [2]=%h [3]=%h [4]=%h",
                tb.dut.tile.ram.mem_ext.Memory[0],
                tb.dut.tile.ram.mem_ext.Memory[1],
                tb.dut.tile.ram.mem_ext.Memory[2],
                tb.dut.tile.ram.mem_ext.Memory[3],
                tb.dut.tile.ram.mem_ext.Memory[4]);
        end

        rst_n = 0;
        repeat (RESET_CYCLES) @(posedge clk);
        rst_n = 1;
        repeat (SETTLE_CYCLES) @(posedge clk);
        cycle = SETTLE_CYCLES;

        while (cycle < MAX_CYCLES + SETTLE_CYCLES) begin
            @(posedge clk);
            cycle = cycle + 1;

            if (rst_n && any_xz) begin
                if (!xz_reported) begin
                    $display("FAIL: X/Z detected at cycle %0d, time %0t:", cycle - SETTLE_CYCLES, $time);
                    report_xz;
                    xz_reported = 1;
                end
            end

            if (commit_msg_valid === 1'bx && !commit_warned) begin
                $display("WARNING: commit_msg_valid = X (unreset registers in Commit/ROB)");
                commit_warned = 1;
            end

            if (commit_msg_valid) begin
                commit_count = commit_count + 1;
                $display("[%0t] #%0d commit (cycle %0d): pc=%08h rd=x%0d val=%08h is_load=%0b",
                         $time, commit_count, cycle - SETTLE_CYCLES,
                         commit_msg_next_pc,
                         commit_msg_rd_index, commit_msg_rd_value,
                         commit_msg_is_load);
            end
        end

        if (commit_count == 0) $display("FAIL: no commits observed within %0d cycles", MAX_CYCLES);
        else begin
            if (xz_reported) $display("FAIL: X/Z propagation detected");
            else $display("PASS: no X/Z detected, %0d commits", commit_count);
            if (commit_warned) $display("INFO: commit_msg_valid X is expected (see CPU RTL notes)");
        end
        $finish;
    end

endmodule
