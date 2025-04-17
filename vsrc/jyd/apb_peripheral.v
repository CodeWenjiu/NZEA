module apb_peripheral(
    input                       Pclk                    ,
    input                       Prst                    ,
    input       [31:0]          Paddr                   ,
    input                       Pwrite                  ,
    input                       Psel                    ,
    input                       Penable                 ,
    input       [31:0]          Pwdata                  ,
    input       [3 :0]          Pstrb                   ,
    output reg  [31:0]          Prdata                  ,
    output                      Pready                  ,
    output                      Pslverr                 ,
    output reg  [31:0]          LED                     ,
    input       [31:0]          SW1                     ,
    input       [31:0]          SW2                     ,
    output reg  [31:0]          SEG                     
);

wire [31:0] Pdaddr = {Paddr[31:2], 2'b00}; // Align address to 4 bytes

// APB write operation
always @(posedge Pclk or posedge Prst) begin
    if (Prst) begin
        LED <= 32'h00000000; // reset value
        SEG <= 32'h00000000;
    end
    else if (Psel && Penable && Pwrite && (Pdaddr == 32'h20000000)) begin
        if (Pstrb[0]) LED[7:0]   <= Pwdata[7:0]  ;
        if (Pstrb[1]) LED[15:8]  <= Pwdata[15:8] ;
        if (Pstrb[2]) LED[23:16] <= Pwdata[23:16];
        if (Pstrb[3]) LED[31:24] <= Pwdata[31:24];
    end
    else if (Psel && Penable && Pwrite && (Pdaddr == 32'h2000000c)) begin
        if (Pstrb[0]) SEG[7:0]   <= Pwdata[7:0]  ;
        if (Pstrb[1]) SEG[15:8]  <= Pwdata[15:8] ;
        if (Pstrb[2]) SEG[23:16] <= Pwdata[23:16];
        if (Pstrb[3]) SEG[31:24] <= Pwdata[31:24];
    end
end

// APB read operation
always @(posedge Pclk) begin
    if (Psel && !Pwrite && (Pdaddr == 32'h20000000))
        Prdata <= LED;
    else if (Psel && !Pwrite && (Pdaddr == 32'h20000004))
        Prdata <= SW1;
    else if (Psel && !Pwrite && (Pdaddr == 32'h20000008))
        Prdata <= SW2;
    else if (Psel && !Pwrite && (Pdaddr == 32'h2000000c))
        Prdata <= SEG;
    else
        Prdata <= 32'hffffffff;
end

assign Pready  = 1'b1;
assign Pslverr = 1'b0;

endmodule
