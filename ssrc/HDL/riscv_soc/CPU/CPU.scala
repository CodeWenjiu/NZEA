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

class Inst_Comp extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val valid = Input(Bool())
  })
  val code =
    s"""module Inst_Comp(
  |  input clock,
  |  input valid
  |);
  | import "DPI-C" function void inst_comp();
  | always @(posedge clock) begin
  |     if(valid) begin
  |         inst_comp();
  |     end
  | end
  |
  |endmodule
  """

  setInline("Inst_Comp.v", code.stripMargin)
}

class AXI_BRIDGE extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val rresp = Input(UInt(2.W))
    val bresp = Input(UInt(2.W))
  })
  val code =
    s"""module AXI_BRIDGE(
  |  input clock,
  |  input [1:0] rresp,
  |  input [1:0] bresp
  |);
  |import "DPI-C" function void error_waddr();
  |always @(posedge clock) begin
  |    if((rresp == 2'b11) || (bresp == 2'b11)) begin
  |        error_waddr();
  |    end
  |end
  |endmodule
  """

  setInline("AXI_BRIDGE.v", code.stripMargin)
}

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

import peripheral._
import ram._
trait HasCoreModules extends Module{
  val IFU: IFU#Impl
  val IDU: IDU
  val ALU: ALU
  val AGU: AGU
  val LSU: LSU#Impl
  val WBU: WBU
  val REG: REG
  val PipelineCtrl: PipelineCtrl
}

object CoreConnect {
  def apply(core: HasCoreModules): Unit = {
    import core._

    PipelineCtrl.io.IDU_2_REG.valid := IDU.io.IDU_2_EXU.valid
    PipelineCtrl.io.IDU_2_REG.bits := IDU.io.IDU_2_REG

    PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
    PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
    PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
    PipelineCtrl.io.AGU_in := AGU.io.IDU_2_EXU
    PipelineCtrl.io.LSU_in := LSU.io.AGU_2_LSU
    PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

    PipelineCtrl.io.WBU_out := WBU.io.WBU_2_IFU

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
        (to_LSU, AGU.io.IDU_2_EXU, AGU.io.AGU_2_LSU),
        (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
      ),
      PipelineCtrl.io.IDUCtrl
    )

    riscv_soc.bus.pipelineConnect(
      AGU.io.AGU_2_LSU,
      LSU.io.AGU_2_LSU,
      LSU.io.EXU_2_WBU,
      PipelineCtrl.io.AGUCtrl
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
    new CLINT(AddressSet.misaligned(0xa0000048L, 0x10), 985.U)
  )
  val lsram = LazyModule(
    new SRAM(AddressSet.misaligned(0x80000000L, 0x8000000))
  )

  luart.node := xbar
  lclint.node := xbar
  lsram.node := xbar
  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val IFU = LazyIFU.module
    val IDU = Module(new IDU)
    val ALU = Module(new ALU)
    val AGU = Module(new AGU)
    val LSU = LazyLSU.module
    val WBU = Module(new WBU)
    val REG = Module(new REG)
    val PipelineCtrl = Module(new PipelineCtrl)
    
    CoreConnect(this)
  }
}
