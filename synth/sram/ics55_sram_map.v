// techmap template: the intermediate cell `memory_libmap` emits (`$__NZEA_SRAM_`,
// see ics55_sram.txt) onto the ics55 ECOS SRAM macro.
//
// The cell's single port A is synchronous, with a per-bit write enable; the macro
// is a single-port 1RW whose enables are active low:
//   CEB  = 0                     chip always selected (the read is unconditional)
//   GWEB = ~|WR_EN               global write enable: a write iff any bit is written
//   WEB  = ~WR_EN                per-bit write enable
//   MARE = 0, MAR = 0            default timing setting
module \$__NZEA_SRAM_ #(
    parameter INIT = 0
) (
    input  [12:0] PORT_A_ADDR,
    input         PORT_A_CLK,
    output [31:0] PORT_A_RD_DATA,
    input  [31:0] PORT_A_WR_DATA,
    input  [31:0] PORT_A_WR_EN
);
    ics55_ecos_sram_8192x32_m16 mem (
        .A(PORT_A_ADDR),
        .CEB(1'b0),
        .CLK(PORT_A_CLK),
        .D(PORT_A_WR_DATA),
        .GWEB(~(|PORT_A_WR_EN)),
        .WEB(~PORT_A_WR_EN),
        .MARE(1'b0),
        .MAR(4'b0),
        .Q(PORT_A_RD_DATA)
    );
endmodule
