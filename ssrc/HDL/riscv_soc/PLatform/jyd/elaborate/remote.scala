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

class jyd_remote_cpu extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM
    val DRAM = new DRAM
  })

  val IFU = Module(new jydIFU)
  val IDU = Module(new riscv_soc.cpu.IDU)
  val ALU = Module(new riscv_soc.cpu.ALU)
  val LSU = Module(new jydLSU)
  val WBU = Module(new riscv_soc.cpu.WBU)
  val REG = Module(new riscv_soc.cpu.REG)
  val PipelineCtrl = Module(new bus.PipelineCtrl)

  PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
  PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

  PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
  PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
  PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
  PipelineCtrl.io.LSU_in := LSU.io.IDU_2_EXU
  PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

  PipelineCtrl.io.Branch_msg := WBU.io.WBU_2_IFU
  
  val to_LSU = WireDefault(false.B)
  to_LSU := IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_LD || IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_ST

  IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
  riscv_soc.bus.pipelineConnect(
    IFU.io.IFU_2_IDU,
    IDU.io.IFU_2_IDU,
    IDU.io.IDU_2_EXU,
    PipelineCtrl.io.IFUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    IDU.io.IDU_2_EXU,
    Seq(
      (to_LSU, LSU.io.IDU_2_EXU, LSU.io.EXU_2_WBU),
      (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
    ),
    PipelineCtrl.io.IDUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    Seq(
      (LSU.io.EXU_2_WBU),
      (ALU.io.EXU_2_WBU)
    ),
    WBU.io.EXU_2_WBU,
    WBU.io.WBU_2_IFU,
    PipelineCtrl.io.EXUCtrl
  )

  WBU.io.WBU_2_IFU.ready := true.B
  REG.io.REG_2_IDU <> IDU.io.REG_2_IDU
  IDU.io.IDU_2_REG <> REG.io.IDU_2_REG
  IFU.io.WBU_2_IFU <> WBU.io.WBU_2_IFU.bits
  WBU.io.WBU_2_REG <> REG.io.WBU_2_REG
  LSU.io.flush := PipelineCtrl.io.EXUCtrl.flush

  io.IROM <> IFU.io.IROM
  io.DRAM <> LSU.io.DRAM
}

