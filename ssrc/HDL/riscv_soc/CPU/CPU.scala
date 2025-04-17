package riscv_soc

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

import bus._
import signal_value._
import config._
import riscv_soc.cpu._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.platform.jyd.ApbPeripheralWrapper
import freechips.rocketchip.amba.apb.APBFanout
import _root_.riscv_soc.platform

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
  val LSU: LSU#Impl
  val WBU: WBU
  val REG: REG
  val PipelineCtrl: PipelineCtrl
}

object CoreConnect {
  def apply(core: HasCoreModules): Unit = {
    // 将原 Trait 中的连接逻辑移到这里
    // 使用 core.X 或 import core._ 来访问成员
    import core._

    PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
    PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

    PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
    PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
    PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
    PipelineCtrl.io.LSU_in := LSU.io.IDU_2_EXU
    PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

    PipelineCtrl.io.Branch_msg := WBU.io.WBU_2_IFU

    val to_LSU = WireDefault(false.B)
    to_LSU := IDU.io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_LD || IDU.io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_ST

    // bus IFU -> IDU
    IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
    pipelineConnect(
      IFU.io.IFU_2_IDU,
      IDU.io.IFU_2_IDU,
      IDU.io.IDU_2_EXU,
      PipelineCtrl.io.IFUCtrl
    )
    pipelineConnect(
      IDU.io.IDU_2_EXU,
      Seq(
        (to_LSU, LSU.io.IDU_2_EXU, LSU.io.EXU_2_WBU),
        (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
      ),
      PipelineCtrl.io.IDUCtrl
    )
    pipelineConnect(
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
    WBU.io.WBU_2_IFU.bits <> IFU.io.WBU_2_IFU
    WBU.io.WBU_2_REG <> REG.io.WBU_2_REG
    LSU.io.flush := PipelineCtrl.io.EXUCtrl.flush
  }
}

class riscv_CPU(idBits: Int)(implicit p: Parameters) extends LazyModule {
  val mmio = AddressSet.misaligned(0x0f000000, 0x2000) ++ // SRAM
    AddressSet.misaligned(0x10000000, 0x1000) ++ // UART
    AddressSet.misaligned(0x10001000, 0x1000) ++ // SPI
    AddressSet.misaligned(0x10002000, 0x10) ++ // GPIO
    AddressSet.misaligned(0x10011000, 0x8) ++ // PS2
    AddressSet.misaligned(0x21000000, 0x200000) ++ // VGA
    AddressSet.misaligned(0x30000000, 0x10000000) ++ // FLASH
    AddressSet.misaligned(0x80000000L, 0x400000) ++ // PSRAM
    AddressSet.misaligned(0xa0000000L, 0x2000000) ++ // SDRAM
    AddressSet.misaligned(0xc0000000L, 0x40000000L) // ChipLink

  ElaborationArtefacts.add("graphml", graphML)
  val LazyIFU = LazyModule(new IFU(idBits = idBits - 1))
  // val LazyEXU = LazyModule(new EXU(idBits = idBits-1))
  val LazyLSU = LazyModule(new LSU(idBits = idBits - 1))

  val xbar = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
  xbar := LazyIFU.masterNode
  xbar := LazyLSU.masterNode

  val lclint = LazyModule(
    new CLINT(AddressSet.misaligned(0x02000048L, 0x10), 985.U)
  )

  lclint.node := xbar

  val beatBytes = 4
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = mmio,
            executable = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  node := xbar

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val io = IO(new Bundle {
      val master = AXI4Bundle(CPUAXI4BundleParameters())
      val slave = Flipped(AXI4Bundle(CPUAXI4BundleParameters()))
      val interrupt = Input(Bool())
    })
    val IFU = LazyIFU.module
    val IDU = Module(new IDU)
    val ALU = Module(new ALU)
    val LSU = LazyLSU.module
    val WBU = Module(new WBU)
    val REG = Module(new REG)
    val PipelineCtrl = Module(new PipelineCtrl)

    io.master <> node.in(0)._1
    io.slave <> DontCare
    io.interrupt <> DontCare

    CoreConnect(this)
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
    val LSU = LazyLSU.module
    val WBU = Module(new WBU)
    val REG = Module(new REG)
    val PipelineCtrl = Module(new PipelineCtrl)
    
    CoreConnect(this)
  }
}
