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

        val csr_wen = Input(UInt(1.W))
        val csr_waddr = Input(UInt(32.W))
        val csr_wdata = Input(UInt(32.W))

        val is_trap = Input(UInt(1.W))
        val trap_type = Input(UInt(32.W))
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
    |    input bit csr_wen,
    |    input [31:0] csr_waddr,
    |    input [31:0] csr_wdata,
    |
    |    input bit is_trap,
    |    input [31:0] trap_type
    |);
    |
    |   import "DPI-C" function void WBU_catch(input bit [31:0] next_pc, input bit [31:0] gpr_waddr, input bit [31:0] gpr_wdata, input bit csr_wen, input bit [31:0] csr_waddr, input bit [31:0] csr_wdata, input bit is_trap, input bit [31:0] trap_type);
    |   always @(posedge clock) begin
    |       if(valid) begin
    |           WBU_catch(next_pc, gpr_waddr, gpr_wdata, csr_wen, csr_waddr, csr_wdata, is_trap, trap_type);
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

        val WBU_2_IFU = Decoupled(Output(new WBU_2_BPU))

        val WBU_2_REG = ValidIO(new WBU_2_REG)
        val REG_2_WBU = Input(new REG_2_WBU)

        val WB_Bypass = ValidIO(Output(new WB_Bypass))
    })

    io.EXU_2_WBU.ready := io.WBU_2_IFU.ready
    io.WBU_2_IFU.valid := io.EXU_2_WBU.valid

    val ex_basic = io.EXU_2_WBU.bits.basic

    io.WBU_2_REG.bits.basic := io.EXU_2_WBU.bits.basic

    io.WBU_2_REG.valid := io.EXU_2_WBU.fire

    val Default_Next_Pc = ex_basic.pc + 4.U

    val next_pc = MuxCase(Default_Next_Pc, Seq(
        (ex_basic.trap.traped) -> io.REG_2_WBU.MTVEC,
        (io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Jump) -> io.EXU_2_WBU.bits.Result,
    ))
    io.WBU_2_IFU.bits.pc := ex_basic.pc
    io.WBU_2_IFU.bits.npc := next_pc

    io.WBU_2_IFU.bits.wb_ctrlflow := MuxCase(WbControlFlow.BPRight, Seq(
        (ex_basic.trap.traped) -> WbControlFlow.Trap,
        (next_pc =/= ex_basic.npc) -> WbControlFlow.BPError,
    ))

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

        Catch.io.csr_wen := io.EXU_2_WBU.bits.wbCtrl === WbCtrl.Csr
        Catch.io.csr_waddr := io.EXU_2_WBU.bits.CSR_waddr
        Catch.io.csr_wdata := io.EXU_2_WBU.bits.Result

        Catch.io.is_trap := ex_basic.trap.traped
        Catch.io.trap_type := ex_basic.trap.trap_type.asUInt
    }
}
