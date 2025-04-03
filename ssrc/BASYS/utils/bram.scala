package BASYS

import chisel3._
import chisel3.util._

class BRAM_sim(width_addr: Int, width_data: Int, name: String) extends BlackBox with HasBlackBoxInline{
    val io = IO(new Bundle{
        val clka = Input(Clock())
        val ena    = Input(Bool())
        val addra  = Input(UInt(width_addr.W))
        val douta  = Output(UInt(width_data.W))
    })

    val func = s"bram_${name}_read"

    val code =
        s"""
        |module BRAM_${name}_sim(
        |    input wire clka,
        |    input wire ena,
        |    input wire [${width_addr-1}:0] addra,
        |    output wire [${width_data-1}:0] douta
        |);
        |    import "DPI-C" function void ${func}(input int raddr, output int rdata);
        |    always @(posedge clka) begin
        |        if (ena) ${func}({${32 - width_addr}'b0, addra}, {${32 - width_data}'b0, douta});
        |    end
        |endmodule
        """

    setInline(s"BRAM_sim${name}.v", code.stripMargin)
}

class BRAM(width_addr: Int, width_data: Int) extends BlackBox{
    val io = IO(new Bundle{
        val clka = Input(Clock())
        val ena = Input(Bool())
        val addra = Input(UInt(width_addr.W))
        val douta = Output(UInt(width_data.W))
    })
}

class BRAM_0(width_addr: Int, width_data: Int) extends BRAM(width_addr = width_addr, width_data = width_data)

class BRAM_P extends BRAM(width_addr = 9, width_data = 12)
