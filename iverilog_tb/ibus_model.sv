// Behavioral instruction-bus model: loads hex file into memory,
// responds to LiteBusRO reads with 2-cycle pipeline latency (matching DPI bridge).

`timescale 1ns / 1ps

module ibus_model #(
    parameter PC_BASE  = 32'h8000_0000,
    parameter MEM_FILE = "hello.hex"
) (
    input  wire        clk,
    input  wire        rst_n,

    input  wire        req_valid,
    output wire        req_ready,
    input  wire [31:0] req_addr,
    input  wire [63:0] req_user,
    output wire        req_flush,

    output wire        resp_valid,
    input  wire        resp_ready,
    output wire [31:0] resp_data,
    output wire [63:0] resp_user,
    input  wire        resp_flush
);
    localparam MEM_DEPTH = 256;
    localparam IDX_W = $clog2(MEM_DEPTH);

    reg [31:0] mem [0:MEM_DEPTH-1];

    // pipeline registers
    reg        s1_valid, s2_valid;
    reg [31:0] s1_data,  s2_data;
    reg [63:0] s1_user,  s2_user;

    // ---- continuous assignments ----
    assign req_flush = resp_flush;

    wire s2_ready = ~s2_valid || resp_ready;
    assign req_ready = ~s1_valid || s2_ready;

    wire fire    = req_valid && req_ready;
    wire s1_fire = s1_valid && s2_ready;

    wire [31:0] idx   = (req_addr - PC_BASE) >> 2;
    wire [31:0] rdata = mem[idx[IDX_W-1:0]];

    assign resp_valid = s2_valid;
    assign resp_data  = s2_data;
    assign resp_user  = s2_user;

    // ---- memory init ----
    integer i;
    initial begin
        for (i = 0; i < MEM_DEPTH; i = i + 1) mem[i] = 32'h0;
        $readmemh(MEM_FILE, mem);
    end

    // ---- pipeline state ----
    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            s1_valid <= 1'b0; s1_data <= 32'h0; s1_user <= 64'h0;
            s2_valid <= 1'b0; s2_data <= 32'h0; s2_user <= 64'h0;
        end else begin
            // stage 1: capture on req.fire (pulse to mimic DPI bridge combinational path)
            if (resp_flush)
                s1_valid <= 1'b0;
            else if (fire) begin
                s1_valid <= 1'b1;
                s1_data  <= rdata;
                s1_user  <= req_user;
            end else
                s1_valid <= 1'b0;
        end
    end

endmodule
