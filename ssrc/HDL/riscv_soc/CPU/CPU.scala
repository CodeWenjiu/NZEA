package riscv_soc

import chisel3._
import chisel3.util._

import bus._
import signal_value._
import riscv_soc.cpu._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

import org.chipsalliance.cde.config.{Parameters, Config}

import _root_.peripheral.UART
import riscv_soc.bus.ChipLinkParam.mem
import chisel3.util.experimental.loadMemoryFromFile

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

trait HasCoreModules extends Module {
  val IFU: frontend.IFU
  val IDU: frontend.IDU
  val ISU: frontend.ISU

  val ALU: backend.ALU
  val LSU: backend.LSU_2
  val WBU: backend.WBU
  
  val REG: REG
  val PipelineCtrl: PipelineCtrl
}

object CoreConnect {
  def apply(core: HasCoreModules): Unit = {
    import core._

    PipelineCtrl.io.GPR_READMSG.valid := IDU.io.IDU_2_ISU.valid
    PipelineCtrl.io.GPR_READMSG.bits := IDU.io.IDU_GPR_READMSG

    PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
    PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
    PipelineCtrl.io.ISU_in := ISU.io.IDU_2_ISU
    PipelineCtrl.io.ALU_in := ALU.io.ISU_2_ALU
    PipelineCtrl.io.LSU_in := LSU.io.ISU_2_LSU
    PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

    PipelineCtrl.io.WBU_out := WBU.io.WBU_2_IFU

    IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
    riscv_soc.bus.pipelineConnect(
      IFU.io.IFU_2_IDU,
      IDU.io.IFU_2_IDU,
      IDU.io.IDU_2_ISU,
      PipelineCtrl.io.IFUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      IDU.io.IDU_2_ISU,
      ISU.io.IDU_2_ISU,
      Seq(ISU.io.ISU_2_ALU, ISU.io.ISU_2_LSU),
      PipelineCtrl.io.IDUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      ISU.io.ISU_2_ALU,
      ALU.io.ISU_2_ALU,
      ALU.io.ALU_2_WBU,
      PipelineCtrl.io.ISU_2_ALUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      ISU.io.ISU_2_LSU,
      LSU.io.ISU_2_LSU,
      LSU.io.LSU_2_WBU,
      PipelineCtrl.io.ISU_2_LSUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      Seq(
        (LSU.io.LSU_2_WBU),
        (ALU.io.ALU_2_WBU)
      ),
      WBU.io.EXU_2_WBU,
      WBU.io.WBU_2_IFU,
      PipelineCtrl.io.EXUCtrl
    )

    REG.io.IDU_2_REG <> IDU.io.IDU_2_REG
    REG.io.REG_2_IDU <> IDU.io.REG_2_IDU

    REG.io.ISU_2_REG <> ISU.io.ISU_2_REG
    REG.io.REG_2_ISU <> ISU.io.REG_2_ISU

    REG.io.WBU_2_REG <> WBU.io.WBU_2_REG
    REG.io.REG_2_WBU <> WBU.io.REG_2_WBU

    LSU.io.Pipeline_ctrl := PipelineCtrl.io.EXUCtrl

    IDU.io.WB_Bypass <> WBU.io.WB_Bypass

    IFU.io.WBU_2_IFU.bits <> WBU.io.WBU_2_IFU.bits
    IFU.io.WBU_2_IFU.valid := WBU.io.WBU_2_IFU.valid

    WBU.io.WBU_2_IFU.ready := true.B
  }
}

class npc(idBits: Int, try_true: Boolean)(implicit p: Parameters) extends LazyModule {
  ElaborationArtefacts.add("graphml", graphML)

  val if_axi = AXI4MasterNode(p(ExtIn).map(params => AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      name = "ifu",
      id   = IdRange(0, 1 << idBits)
    )
  ))).toSeq)

  val ls_axi = AXI4MasterNode(p(ExtIn).map(params => AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      name = "lsu",
      id   = IdRange(0, 1 << idBits)
    )
  ))).toSeq)

  val xbar = AXI4Xbar(
    maxFlightPerId = 1, 
    awQueueDepth = 1
  )
  xbar := if_axi
  xbar := ls_axi

  val luart = LazyModule(new UART(AddressSet.misaligned(0x10000000, 0x10000)))
  val lclint = LazyModule(new peripheral.CLINT(AddressSet.misaligned(0xa0000048L, 0x10), 985.U))
  luart.node := xbar
  lclint.node := xbar
  
  val sram = LazyModule(new ram.SRAM(AddressSet.misaligned(0x80000000L, 0x8000000)))
  sram.node := xbar

  if (try_true) {
    val lsram = LazyModule(new AXI4RAM(AddressSet.misaligned(0xb0000000L, 0x20000).head, false, true, 4, None, Nil, false))
    lsram.node := xbar
  }
  
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val IFU = Module(new frontend.IFU)
    if_axi.out(0)._1 <> IFU.io.bus.toAXI(0)

    val IDU = Module(new frontend.IDU)
    val ISU = Module(new frontend.ISU)

    val ALU = Module(new backend.ALU)
    val LSU = Module(new backend.LSU_2)
    ls_axi.out(0)._1 <> LSU.io.bus.bufferd.toAXI(1 << (idBits-1))
    val WBU = Module(new backend.WBU)
    
    val REG = Module(new REG)
    val PipelineCtrl = Module(new PipelineCtrl)

    CoreConnect(this)
  }
}
