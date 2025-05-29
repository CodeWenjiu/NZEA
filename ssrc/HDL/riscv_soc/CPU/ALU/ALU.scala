package riscv_soc.cpu

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import config._

class ALU_n extends Module {
    val io = IO(new Bundle {
        val ISU_2_ALU = Flipped(Decoupled(Input(new ISU_2_ALU)))

        val ALU_2_WBU = Decoupled(Output(new EXU_2_WBU))
    })

    io.ALU_2_WBU.valid := io.ISU_2_ALU.valid
    io.ISU_2_ALU.ready  := io.ALU_2_WBU.ready

    io.ALU_2_WBU.bits.PC := io.ISU_2_ALU.bits.PC
    io.ALU_2_WBU.bits.trap := io.ISU_2_ALU.bits.trap

    val srca = io.ISU_2_ALU.bits.SRCA
    val srcb = io.ISU_2_ALU.bits.SRCB
    io.ALU_2_WBU.bits.Result := MuxLookup(io.ISU_2_ALU.bits.al_ctrl, 0.U)(Seq(
        AlCtrl.ADD -> (srca + srcb),
        AlCtrl.SUB -> (srca - srcb),

        AlCtrl.AND -> (srca & srcb),
        AlCtrl.OR -> (srca | srcb),
        AlCtrl.XOR -> (srca ^ srcb),

        AlCtrl.SLL -> (srca << srcb(4, 0))(31, 0),
        AlCtrl.SRL -> (srca >> srcb(4, 0))(31, 0),
        AlCtrl.SRA -> (srca.asSInt >> srcb(4, 0))(31, 0),
    ))

    io.ALU_2_WBU.bits.CSR_rdata := 0.U
    
    io.ALU_2_WBU.bits.gpr_waddr := io.ISU_2_ALU.bits.gpr_waddr
    io.ALU_2_WBU.bits.CSR_waddr := 0.U

    io.ALU_2_WBU.bits.wbCtrl := io.ISU_2_ALU.bits.wb_ctrl

    if(Config.Simulate) {
        val Catch = Module(new ALU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.ALU_2_WBU.fire && !reset.asBool
        Catch.io.pc    := io.ISU_2_ALU.bits.PC
    }
}
