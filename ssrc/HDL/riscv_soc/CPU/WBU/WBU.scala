package riscv_soc.cpu

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
        val EXU_2_WBU = Flipped(Decoupled(Input(new BUS_EXU_2_WBU)))
        val WBU_2_IFU = Decoupled(Output(new BUS_WBU_2_IFU))
        val WBU_2_REG = Output(new BUS_WBU_2_REG)
    })

    io.EXU_2_WBU.ready := io.WBU_2_IFU.ready
    io.WBU_2_IFU.valid := io.EXU_2_WBU.valid

    when(io.EXU_2_WBU.valid && io.EXU_2_WBU.ready){
        io.WBU_2_REG.inst_valid := true.B
    }.otherwise{
        io.WBU_2_REG.inst_valid := false.B
    }
    
    val Default_Next_Pc = io.EXU_2_WBU.bits.PC + 4.U

    val Next_Pc = MuxLookup(io.EXU_2_WBU.bits.Branch, io.EXU_2_WBU.bits.Jmp_Pc)(Seq(
        Bran_TypeEnum.Bran_Jeq -> Mux(io.EXU_2_WBU.bits.Result === 0.U, io.EXU_2_WBU.bits.Jmp_Pc, Default_Next_Pc),
        Bran_TypeEnum.Bran_Jne -> Mux(io.EXU_2_WBU.bits.Result === 0.U, Default_Next_Pc, io.EXU_2_WBU.bits.Jmp_Pc),
        Bran_TypeEnum.Bran_Jlt -> Mux(io.EXU_2_WBU.bits.Result(0), io.EXU_2_WBU.bits.Jmp_Pc, Default_Next_Pc),
        Bran_TypeEnum.Bran_Jge -> Mux(io.EXU_2_WBU.bits.Result(0), Default_Next_Pc, io.EXU_2_WBU.bits.Jmp_Pc),
        Bran_TypeEnum.Bran_NJmp -> Default_Next_Pc,
    ))

    val GPR_wdata = MuxLookup(io.EXU_2_WBU.bits.MemtoReg, io.EXU_2_WBU.bits.Result)(Seq(
        Y  -> io.EXU_2_WBU.bits.Mem_rdata,
        N  -> Mux(io.EXU_2_WBU.bits.csr_ctr === CSR_TypeEnum.CSR_N, Mux(io.EXU_2_WBU.bits.Branch === Bran_TypeEnum.Bran_Jmpr || io.EXU_2_WBU.bits.Branch === Bran_TypeEnum.Bran_Jmp, Default_Next_Pc, io.EXU_2_WBU.bits.Result), io.EXU_2_WBU.bits.CSR_rdata),
    ))

    val CSR_waddra = MuxLookup(io.EXU_2_WBU.bits.csr_ctr, io.EXU_2_WBU.bits.CSR_waddr)(Seq(
        CSR_TypeEnum.CSR_R1W2 -> "h341".U
    ))

    val CSR_wdataa = MuxLookup(io.EXU_2_WBU.bits.csr_ctr, io.EXU_2_WBU.bits.Result)(Seq(
        CSR_TypeEnum.CSR_R1W2 -> io.EXU_2_WBU.bits.PC,
    ))

    io.WBU_2_IFU.bits.Next_PC := Next_Pc

    io.WBU_2_REG.GPR_waddr     := io.EXU_2_WBU.bits.GPR_waddr
    io.WBU_2_REG.GPR_wdata     := GPR_wdata
    io.WBU_2_REG.CSR_ctr       <> io.EXU_2_WBU.bits.csr_ctr
    io.WBU_2_REG.CSR_waddra    := CSR_waddra
    io.WBU_2_REG.CSR_waddrb    := "h342".U
    io.WBU_2_REG.CSR_wdataa    := CSR_wdataa
    io.WBU_2_REG.CSR_wdatab    := 11.U

    if(Config.Simulate){
        val Catch = Module(new WBU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.WBU_2_IFU.fire && !reset.asBool

        Catch.io.next_pc := Next_Pc
        
        Catch.io.gpr_waddr := io.EXU_2_WBU.bits.GPR_waddr
        Catch.io.gpr_wdata := GPR_wdata

        Catch.io.csr_wena := io.EXU_2_WBU.bits.csr_ctr =/= CSR_TypeEnum.CSR_N
        Catch.io.csr_waddra := CSR_waddra
        Catch.io.csr_wdataa := CSR_wdataa
        
        Catch.io.csr_wenb := io.EXU_2_WBU.bits.csr_ctr === CSR_TypeEnum.CSR_R1W2
        Catch.io.csr_waddrb := "h342".U
        Catch.io.csr_wdatab := 11.U
    }
}

class WBU_n extends Module {
    val io = IO(new Bundle{
        val EXU_2_WBU = Flipped(Decoupled(Input(new EXU_2_WBU)))

        val WBU_2_IFU = Decoupled(Output(new BUS_WBU_2_IFU))

        val WBU_2_REG = ValidIO(new WBU_2_REG)
        val REG_2_WBU = Input(new REG_2_WBU)
    })

    io.EXU_2_WBU.ready := io.WBU_2_IFU.ready
    io.WBU_2_IFU.valid := io.EXU_2_WBU.valid

    io.WBU_2_REG.bits.trap := io.EXU_2_WBU.bits.trap
    
    io.WBU_2_REG.valid := io.EXU_2_WBU.fire

    val Default_Next_Pc = io.EXU_2_WBU.bits.PC + 4.U

    io.WBU_2_IFU.bits.Next_PC := MuxCase(Default_Next_Pc, Seq(
        (io.EXU_2_WBU.bits.trap.traped) -> io.REG_2_WBU.MTVEC,
        (io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Jump) -> io.EXU_2_WBU.bits.Result,
    ))

    io.WBU_2_REG.bits.GPR_waddr := io.EXU_2_WBU.bits.GPR_waddr
    io.WBU_2_REG.bits.GPR_wdata := MuxLookup(io.EXU_2_WBU.bits.wbCtrl, io.EXU_2_WBU.bits.Result)(Seq(
        WbCtrl.Write_GPR -> io.EXU_2_WBU.bits.Result,
        WbCtrl.Jump -> Default_Next_Pc, // link to register
        WbCtrl.Csr  -> io.EXU_2_WBU.bits.CSR_rdata,
    ))

    io.WBU_2_REG.bits.CSR_wen := io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Csr
    io.WBU_2_REG.bits.CSR_waddr := io.EXU_2_WBU.bits.CSR_waddr
    io.WBU_2_REG.bits.CSR_wdata := io.EXU_2_WBU.bits.Result
}
