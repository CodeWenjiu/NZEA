package riscv_soc.cpu

import chisel3._
import chisel3.util._

import riscv_soc.bus._

import config._

class AGU extends Module {
    val io = IO(new Bundle{
        val IDU_2_EXU = Flipped(Decoupled(Input(new BUS_IDU_2_EXU)))
        val AGU_2_LSU = Decoupled(Output(new BUS_AGU_2_LSU))
    })

    io.AGU_2_LSU.valid := io.IDU_2_EXU.valid
    io.IDU_2_EXU.ready := io.AGU_2_LSU.ready

    val addr = WireDefault(io.IDU_2_EXU.bits.EXU_A + io.IDU_2_EXU.bits.Imm)
    val data = WireDefault(io.IDU_2_EXU.bits.EXU_B)
    val mask = MuxLookup(io.IDU_2_EXU.bits.MemOp, 0.U)(Seq(
        MemOp_TypeEnum.MemOp_1BS -> "b00".U,
        MemOp_TypeEnum.MemOp_1BU -> "b00".U,
        MemOp_TypeEnum.MemOp_2BS -> "b01".U,
        MemOp_TypeEnum.MemOp_2BU -> "b01".U,
        MemOp_TypeEnum.MemOp_4BU -> "b10".U,
    ))

    io.AGU_2_LSU.bits.addr := addr
    io.AGU_2_LSU.bits.wdata := data
    io.AGU_2_LSU.bits.wen := io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_ST
    io.AGU_2_LSU.bits.mask := mask

    io.AGU_2_LSU.bits.PC := io.IDU_2_EXU.bits.PC
    io.AGU_2_LSU.bits.GPR_waddr := io.IDU_2_EXU.bits.GPR_waddr
}
