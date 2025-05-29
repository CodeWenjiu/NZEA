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

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

trait HasCoreModules extends Module {
  val IFU: IFU#Impl
  val IDU: IDU
  val ISU: ISU
  val ALU: ALU
  val LSU: LSU#Impl
  val WBU: WBU
  val REG: REG
  val PipelineCtrl: PipelineCtrl
}

object CoreConnect {
  def apply(core: HasCoreModules): Unit = {
    import core._

    PipelineCtrl.io.IDU_2_REG.valid := IDU.io.IDU_2_ISU.valid
    PipelineCtrl.io.IDU_2_REG.bits := IDU.io.IDU_2_ISU.bits

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
    REG.io.WBU_2_REG <> WBU.io.WBU_2_REG
    REG.io.REG_2_WBU <> WBU.io.REG_2_WBU

    LSU.io.is_flush := PipelineCtrl.io.EXUCtrl.flush

    WBU.io.WBU_2_IFU.ready := true.B
    IFU.io.WBU_2_IFU <> WBU.io.WBU_2_IFU.bits
  }
}

class npc(idBits: Int)(implicit p: Parameters) extends LazyModule {
  ElaborationArtefacts.add("graphml", graphML)
  val LazyIFU = LazyModule(new IFU(idBits = idBits))
  val LazyLSU = LazyModule(new LSU(idBits = idBits))

  val xbar = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
  xbar := LazyIFU.masterNode
  xbar := LazyLSU.masterNode

  val luart = LazyModule(new UART(AddressSet.misaligned(0x10000000, 0x1000)))
  val lclint = LazyModule(
    new peripheral.CLINT(AddressSet.misaligned(0xa0000048L, 0x10), 985.U)
  )
  val lsram = LazyModule(
    new ram.SRAM(AddressSet.misaligned(0x80000000L, 0x8000000))
  )

  luart.node := xbar
  lclint.node := xbar
  lsram.node := xbar
  
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val IFU = LazyIFU.module
    val IDU = Module(new IDU)
    val ISU = Module(new ISU)
    val ALU = Module(new ALU)
    val LSU = LazyLSU.module
    val WBU = Module(new WBU)
    val REG = Module(new REG)
    val PipelineCtrl = Module(new PipelineCtrl)

    CoreConnect(this)
  }
}
