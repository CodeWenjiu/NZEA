module apb_peripheral(
    input                       Pclk                    ,
    input                       Prst                  ,
    input       [31:0]          Paddr                   ,
    input                       Pwrite                  ,
    input                       Psel                    ,
    input                       Penable                 ,
    input       [31:0]          Pwdata                  ,
    output reg  [31:0]          Prdata                  ,
    output                      Pready                  ,
    output                      Pslverr                 ,
    output reg  [31:0]          LED                     ,
    input       [31:0]          SW1                     ,
    input       [31:0]          SW2                     ,
    output reg  [31:0]          SEG                     
);

// APB write operation
always @(posedge Pclk or posedge Prst) begin
    if (!Prst)
        LED <= 32'hff00ff00; // reset value
    else if (Psel && Penable && Pwrite && (Paddr == 32'h20000000))
        LED <= Pwdata;
    else if (Psel && Penable && Pwrite && (Paddr == 32'h2000000c))
        SEG <= Pwdata;
end

// APB read operation
always @(posedge Pclk or posedge Prst) begin
    if (Psel && !Pwrite && (Paddr == 32'h20000000))
        Prdata = LED;
    else if (Psel && !Pwrite && (Paddr == 32'h20000004))
        Prdata = SW1;
    else if (Psel && !Pwrite && (Paddr == 32'h20000008))
        Prdata = SW2;
    else if (Psel && !Pwrite && (Paddr == 32'h2000000c))
        Prdata = SEG;
    else
        Prdata = 32'h00000000;
end

assign Pready  = 1'b1;
assign Pslverr = 1'b0;

endmodule
