// Behavioral data-bus model: RW memory for CPU data access.
// Responds to LiteBusRW reads/writes with 2-cycle pipeline latency (matching DPI bridge).

module dbus_model #(
    parameter MEM_FILE = "data.hex"
) (
    input  wire        clk,
    input  wire        rst_n,

    input  wire        req_valid,
    output wire        req_ready,
    input  wire [31:0] req_addr,
    input  wire [31:0] req_wdata,
    input  wire        req_wen,
    input  wire [3:0]  req_wstrb,
    input  wire [31:0] req_user,
    output wire        req_flush,

    output wire        resp_valid,
    input  wire        resp_ready,
    output wire [31:0] resp_data,
    output wire [31:0] resp_user,
    input  wire        resp_flush
);
    localparam MEM_DEPTH = 256;

    reg [31:0] mem [0:MEM_DEPTH-1];
    reg        s1_valid, s2_valid;
    reg [31:0] s1_data,  s2_data;
    reg [31:0] s1_user,  s2_user;

    wire is_read          = ~req_wen;
    wire [31:0] word_addr = req_addr[31:2];

    wire s2_ready = ~s2_valid || resp_ready;
    assign req_ready = ~s1_valid || s2_ready;
    assign req_flush  = resp_flush;

    wire fire    = req_valid && req_ready;
    wire s1_fire = s1_valid && s2_ready;

    assign resp_valid = s2_valid;
    assign resp_data  = s2_data;
    assign resp_user  = s2_user;

    integer i;
    initial begin
        for (i = 0; i < MEM_DEPTH; i = i + 1) mem[i] = 32'h0;
        if (MEM_FILE != "") $readmemh(MEM_FILE, mem);
    end

    always @(posedge clk) begin
        if (fire && req_wen) mem[word_addr] <= req_wdata;
    end

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            s1_valid <= 1'b0; s1_data <= 32'h0; s1_user <= 32'h0;
            s2_valid <= 1'b0; s2_data <= 32'h0; s2_user <= 32'h0;
        end else begin
            if (resp_flush)
                s1_valid <= 1'b0;
            else if (fire) begin
                s1_valid <= 1'b1;
                s1_data  <= is_read ? mem[word_addr] : 32'h0;
                s1_user  <= req_user;
            end else
                s1_valid <= 1'b0;
    end

endmodule
