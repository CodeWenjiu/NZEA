package riscv_soc.platform.jyd

import chisel3._
import chisel3.util._

import config.Config
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import riscv_soc.bus.AXI4ToAPB

import riscv_soc.peripheral._
import scopt.platform
import riscv_soc.HasCoreModules
import riscv_soc.CoreConnect
import freechips.rocketchip.amba.axi4.AXI4Bundle
import riscv_soc.CPUAXI4BundleParameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.bus
import _root_.peripheral.UART
import riscv_soc.cpu.EXUctr_Field

class jydIFU extends Module {
    val io = IO(new Bundle {
        val WBU_2_IFU = Flipped(new riscv_soc.bus.BUS_WBU_2_IFU)
        val IFU_2_IDU = Decoupled(Output(new riscv_soc.bus.BUS_IFU_2_IDU))

        val Pipeline_ctrl = Flipped(new riscv_soc.bus.Pipeline_ctrl)
        val IROM = new IROM_bus
    })

    val pc = RegInit(Config.Reset_Vector)
    val snpc = pc + 4.U
    val dnpc = io.WBU_2_IFU.Next_PC
    
    io.IROM.addr := pc
    pc := MuxCase(pc, Seq(
        (io.Pipeline_ctrl.flush) -> dnpc,
        (io.IFU_2_IDU.fire) -> snpc
    ))

    io.IFU_2_IDU.valid := true.B

    io.IFU_2_IDU.bits.PC := pc
    io.IFU_2_IDU.bits.data := io.IROM.data

    if(Config.Simulate){
        val Catch = Module(new riscv_soc.cpu.IFU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.IFU_2_IDU.fire && !reset.asBool
        Catch.io.inst := io.IFU_2_IDU.bits.data
        Catch.io.pc := io.IFU_2_IDU.bits.PC
    }
}

class jydLSU extends Module {
  val io = IO(new Bundle{
    val AGU_2_LSU = Flipped(Decoupled(Input(new riscv_soc.bus.BUS_AGU_2_LSU)))
    val EXU_2_WBU = Decoupled(Output(new riscv_soc.bus.BUS_EXU_2_WBU))
    val flush = Input(Bool())
    val DRAM = new DRAM_bus
  })

  io.AGU_2_LSU.ready := io.EXU_2_WBU.ready
  io.EXU_2_WBU.valid := io.AGU_2_LSU.valid

  io.DRAM.addr := io.AGU_2_LSU.bits.addr
  io.DRAM.wdata := io.AGU_2_LSU.bits.wdata
  io.DRAM.wen := io.AGU_2_LSU.bits.wen && io.AGU_2_LSU.fire

  val mask = MuxLookup(io.AGU_2_LSU.bits.MemOp, 0.U)(Seq(
      bus.MemOp_TypeEnum.MemOp_1BS -> "b00".U,
      bus.MemOp_TypeEnum.MemOp_1BU -> "b00".U,
      bus.MemOp_TypeEnum.MemOp_2BS -> "b01".U,
      bus.MemOp_TypeEnum.MemOp_2BU -> "b01".U,
      bus.MemOp_TypeEnum.MemOp_4BU -> "b10".U,
  ))
  io.DRAM.mask := mask

  io.EXU_2_WBU.bits.Branch        := bus.Bran_TypeEnum.Bran_NJmp
  io.EXU_2_WBU.bits.Jmp_Pc        := 0.U   
  io.EXU_2_WBU.bits.MemtoReg      := !io.AGU_2_LSU.bits.wen
  io.EXU_2_WBU.bits.csr_ctr       := bus.CSR_TypeEnum.CSR_N  
  io.EXU_2_WBU.bits.CSR_waddr     := 0.U
  io.EXU_2_WBU.bits.GPR_waddr     := io.AGU_2_LSU.bits.GPR_waddr
  io.EXU_2_WBU.bits.PC            := io.AGU_2_LSU.bits.PC     
  io.EXU_2_WBU.bits.CSR_rdata     := 0.U 
  io.EXU_2_WBU.bits.Result        := 0.U
  io.EXU_2_WBU.bits.Mem_rdata     := MuxLookup(io.AGU_2_LSU.bits.MemOp, io.DRAM.rdata)(Seq(
      bus.MemOp_TypeEnum.MemOp_1BS -> Cat(Fill(24, io.DRAM.rdata(7)), io.DRAM.rdata(7,0)),
      bus.MemOp_TypeEnum.MemOp_2BS -> Cat(Fill(16, io.DRAM.rdata(15)), io.DRAM.rdata(15,0)),
  ))

  if(Config.Simulate){
    val Catch = Module(new riscv_soc.cpu.LSU_catch)
    Catch.io.clock := clock
    Catch.io.valid := io.EXU_2_WBU.fire && !reset.asBool
    Catch.io.pc    := io.AGU_2_LSU.bits.PC
    Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(io.AGU_2_LSU.bits.addr)).reduce(_ || _)
  }
}