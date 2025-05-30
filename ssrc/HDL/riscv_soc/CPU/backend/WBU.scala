package riscv_soc.cpu.backend

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import config._

class WBU_catch extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val valid = Input(Bool())
        
        val next_pc = Input(UInt(32.W))

        val gpr_waddr = Input(UInt(32.W))
        val gpr_wdata = Input(UInt(32.W))

        val csr_wena = Input(UInt(32.W))
        val csr_waddra = Input(UInt(32.W))
        val csr_wdataa = Input(UInt(32.W))
        val csr_wenb = Input(UInt(32.W))
        val csr_waddrb = Input(UInt(32.W))
        val csr_wdatab = Input(UInt(32.W))
    })
    val code = 
    s"""module WBU_catch(
    |    input clock,
    |    input valid,
    |
    |    input [31:0] next_pc,
    |
    |    input [31:0] gpr_waddr,
    |    input [31:0] gpr_wdata,
    |
    |    input [31:0] csr_wena,
    |    input [31:0] csr_waddra,
    |    input [31:0] csr_wdataa,
    |    input [31:0] csr_wenb,
    |    input [31:0] csr_waddrb,
    |    input [31:0] csr_wdatab
    |);
    |
    |   import "DPI-C" function void WBU_catch(input bit [31:0] next_pc, input bit [31:0] gpr_waddr, input bit [31:0] gpr_wdata, input bit [31:0] csr_wena, input bit [31:0] csr_waddra, input bit [31:0] csr_wdataa, input bit [31:0] csr_wenb, input bit [31:0] csr_waddrb, input bit [31:0] csr_wdatab);
    |   always @(posedge clock) begin
    |       if(valid) begin
    |           WBU_catch(next_pc, gpr_waddr, gpr_wdata, csr_wena, csr_waddra, csr_wdataa, csr_wenb, csr_waddrb, csr_wdatab);
    |       end
    |   end
    |
    |endmodule
    """

    setInline("WBU_catch.v", code.stripMargin)
}

class WBU extends Module {
    val io = IO(new Bundle{
        val EXU_2_WBU = Flipped(Decoupled(Input(new EXU_2_WBU)))

        val WBU_2_IFU = Decoupled(Output(new WBU_2_IFU))

        val WBU_2_REG = ValidIO(new WBU_2_REG)
        val REG_2_WBU = Input(new REG_2_WBU)

        val WB_Bypass = ValidIO(Output(new WB_Bypass))
    })

    io.EXU_2_WBU.ready := io.WBU_2_IFU.ready
    io.WBU_2_IFU.valid := io.EXU_2_WBU.valid

    io.WBU_2_REG.bits.trap := io.EXU_2_WBU.bits.trap
    
    io.WBU_2_REG.valid := io.EXU_2_WBU.fire

    val Default_Next_Pc = io.EXU_2_WBU.bits.PC + 4.U

    val next_pc = MuxCase(Default_Next_Pc, Seq(
        (io.EXU_2_WBU.bits.trap.traped) -> io.REG_2_WBU.MTVEC,
        (io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Jump) -> io.EXU_2_WBU.bits.Result,
    ))
    io.WBU_2_IFU.bits.next_pc := next_pc

    io.WBU_2_REG.bits.gpr_waddr := io.EXU_2_WBU.bits.gpr_waddr
    val gpr_wdata = MuxLookup(io.EXU_2_WBU.bits.wbCtrl, io.EXU_2_WBU.bits.Result)(Seq(
        WbCtrl.Write_GPR -> io.EXU_2_WBU.bits.Result,
        WbCtrl.Jump -> Default_Next_Pc, // link to register
        WbCtrl.Csr  -> io.EXU_2_WBU.bits.CSR_rdata,
    ))
    io.WBU_2_REG.bits.gpr_wdata := gpr_wdata

    io.WBU_2_REG.bits.CSR_wen := io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Csr
    io.WBU_2_REG.bits.CSR_waddr := io.EXU_2_WBU.bits.CSR_waddr
    io.WBU_2_REG.bits.CSR_wdata := io.EXU_2_WBU.bits.Result

    io.WB_Bypass.valid := io.EXU_2_WBU.fire
    io.WB_Bypass.bits.gpr_waddr := io.EXU_2_WBU.bits.gpr_waddr
    io.WB_Bypass.bits.gpr_wdata := gpr_wdata

    if(Config.Simulate){
        val Catch = Module(new WBU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.WBU_2_IFU.fire && !reset.asBool

        Catch.io.next_pc := next_pc
        
        Catch.io.gpr_waddr := io.EXU_2_WBU.bits.gpr_waddr
        Catch.io.gpr_wdata := gpr_wdata

        Catch.io.csr_wena := false.B
        Catch.io.csr_waddra := 0.U
        Catch.io.csr_wdataa := 0.U
        
        Catch.io.csr_wenb := false.B
        Catch.io.csr_waddrb := "h342".U
        Catch.io.csr_wdatab := 11.U
    }
}
