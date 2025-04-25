package riscv_soc.platform.ysyxsoc

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

import riscv_soc.cpu._

class core(idBits: Int)(implicit p: Parameters) extends LazyModule {
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
    val PipelineCtrl = Module(new bus.PipelineCtrl)

    io.master <> node.in(0)._1
    io.slave <> DontCare
    io.interrupt <> DontCare

    CoreConnect(this)
  }
}

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

class ysyx_23060198 extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)
  
  val io = IO(new Bundle {
      val master = AXI4Bundle(riscv_soc.CPUAXI4BundleParameters())
      val slave  = Flipped(AXI4Bundle(riscv_soc.CPUAXI4BundleParameters()))
      val interrupt = Input(Bool()) 
  })
  val dut = LazyModule(new riscv_soc.platform.ysyxsoc.core(idBits = ChipLinkParam.idBits))
  val mdut = Module(dut.module)

  chisel3.experimental.annotate(
    new chisel3.experimental.ChiselAnnotation {
      override def toFirrtl = sifive.enterprise.firrtl.NestedPrefixModulesAnnotation(mdut.toTarget, "ysyx_23060198_", true)
    }
  )

  mdut.dontTouchPorts()
  
  mdut.io.master <> io.master
  io.slave <> mdut.io.slave
  io.interrupt <> mdut.io.interrupt
}

class top extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)
  
  val io = IO(new Bundle {
      val master = AXI4Bundle(riscv_soc.CPUAXI4BundleParameters())
      val slave  = Flipped(AXI4Bundle(riscv_soc.CPUAXI4BundleParameters()))
      val interrupt = Input(Bool()) 
  })
  val dut = LazyModule(new riscv_soc.platform.ysyxsoc.core(idBits = ChipLinkParam.idBits))
  val mdut = Module(dut.module)

  chisel3.experimental.annotate(
    new chisel3.experimental.ChiselAnnotation {
      override def toFirrtl = sifive.enterprise.firrtl.NestedPrefixModulesAnnotation(mdut.toTarget, "ysyx_23060198_", true)
    }
  )

  mdut.dontTouchPorts()
  
  mdut.io.master <> io.master
  io.slave <> mdut.io.slave
  io.interrupt <> mdut.io.interrupt
}