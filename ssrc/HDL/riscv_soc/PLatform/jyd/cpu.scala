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
import riscv_soc.cpu.backend.LSU_catch

class jydIFU extends Module {
    val io = IO(new Bundle {
        val WBU_2_IFU = Flipped(new riscv_soc.bus.WBU_2_BPU)
        val IFU_2_IDU = Decoupled(Output(new riscv_soc.bus.IFU_2_IDU))

        val Pipeline_ctrl = Flipped(new riscv_soc.bus.Pipeline_ctrl)
        val IROM = new IROM_bus
    })

    val pc = RegInit(Config.Reset_Vector)
    val snpc = pc + 4.U
    val dnpc = io.WBU_2_IFU.next_pc
    
    io.IROM.addr := pc
    pc := MuxCase(pc, Seq(
        (io.Pipeline_ctrl.flush) -> dnpc,
        (io.IFU_2_IDU.fire) -> snpc
    ))

    io.IFU_2_IDU.valid := true.B

    io.IFU_2_IDU.bits.pc := pc
    io.IFU_2_IDU.bits.inst := io.IROM.data

    if(Config.Simulate){
        val Catch = Module(new riscv_soc.cpu.frontend.IFU_catch)
        Catch.io.clock := clock
        Catch.io.valid := io.IFU_2_IDU.fire && !reset.asBool
        Catch.io.inst := io.IFU_2_IDU.bits.inst
        Catch.io.pc := io.IFU_2_IDU.bits.pc
    }
}

class jydLSU extends Module {
  val io = IO(new Bundle{
    val ISU_2_LSU = Flipped(Decoupled(Input(new riscv_soc.bus.ISU_2_LSU)))
    
    val LSU_2_WBU = Decoupled(Output(new riscv_soc.bus.EXU_2_WBU))

    val is_flush = Input(Bool())
    
    val DRAM = new DRAM_bus
  })

  io.ISU_2_LSU.ready := io.LSU_2_WBU.ready
  io.LSU_2_WBU.valid := io.ISU_2_LSU.valid

  io.DRAM.addr := io.ISU_2_LSU.bits.addr
  io.DRAM.wdata := io.ISU_2_LSU.bits.data
  val is_st = MuxLookup(io.ISU_2_LSU.bits.Ctrl, false.B)(Seq(
      bus.LsCtrl.SB -> true.B,
      bus.LsCtrl.SH -> true.B,
      bus.LsCtrl.SW -> true.B
  ))
  io.DRAM.wen := is_st && io.ISU_2_LSU.fire
  val mask = MuxLookup(io.ISU_2_LSU.bits.Ctrl, 0.U)(Seq(
      bus.LsCtrl.SB -> "b00".U,
      bus.LsCtrl.SH -> "b01".U, 
      bus.LsCtrl.SW -> "b10".U,
      bus.LsCtrl.LB -> "b00".U,
      bus.LsCtrl.LH -> "b01".U,
      bus.LsCtrl.LW -> "b10".U,
      bus.LsCtrl.LBU -> "b00".U,
      bus.LsCtrl.LHU -> "b01".U
  ))
  io.DRAM.mask := mask

  val rdata = MuxLookup(io.ISU_2_LSU.bits.Ctrl, io.DRAM.rdata)(Seq(
    bus.LsCtrl.LB -> Cat(Fill(24, io.DRAM.rdata(7)), io.DRAM.rdata(7,0)),
    bus.LsCtrl.LH -> Cat(Fill(16, io.DRAM.rdata(15)), io.DRAM.rdata(15,0)),
  ))

  io.LSU_2_WBU.bits.basic.pc := io.ISU_2_LSU.bits.basic.pc
  io.LSU_2_WBU.bits.basic.trap.traped := false.B
  io.LSU_2_WBU.bits.basic.trap.trap_type := bus.Trap_type.Ebreak

  io.LSU_2_WBU.bits.Result := rdata
  io.LSU_2_WBU.bits.CSR_rdata := 0.U

  io.LSU_2_WBU.bits.gpr_waddr := io.ISU_2_LSU.bits.gpr_waddr
  io.LSU_2_WBU.bits.CSR_waddr := 0.U
  io.LSU_2_WBU.bits.wbCtrl := bus.WbCtrl.Write_GPR

  if(Config.Simulate) {
      val Catch = Module(new LSU_catch)
      Catch.io.clock := clock
      Catch.io.valid := io.LSU_2_WBU.fire && !reset.asBool
      Catch.io.pc    := io.ISU_2_LSU.bits.basic.pc
      Catch.io.diff_skip := Config.diff_mis_map.map(_.contains(io.ISU_2_LSU.bits.addr)).reduce(_ || _)
  }
}