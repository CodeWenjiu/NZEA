package riscv_soc.platform.jyd.on_board
import riscv_soc.platform.jyd._

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

class jyd(idBits: Int)(implicit p: Parameters) extends LazyModule {
  ElaborationArtefacts.add("graphml", graphML)
  val LazyIFU = LazyModule(new riscv_soc.cpu.IFU(idBits = idBits - 1))
  val LazyLSU = LazyModule(new riscv_soc.cpu.LSU(idBits = idBits - 1))

  val xbar = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
  val apbxbar = LazyModule(new APBFanout).node
  xbar := LazyIFU.masterNode
  xbar := LazyLSU.masterNode

  apbxbar := AXI4ToAPB() := xbar

  if (Config.Simulate) {
    val luart = LazyModule(new UART(AddressSet.misaligned(0x10000000, 0x1000)))
    val lsram = LazyModule(
      new ram.SRAM(AddressSet.misaligned(0x80000000L, 0x8000000))
    )

    luart.node := xbar
    lsram.node := xbar
  } else {
    val lsram = LazyModule(
      new SystemRAMWrapper(
        AddressSet.misaligned(0x80000000L, 0x8000000)
      )
    )
    lsram.node := xbar
  }

  val lclint = LazyModule(
    new CLINT(AddressSet.misaligned(0xa0000048L, 0x10), 985.U)
  )
  lclint.node := xbar

  val lperipheral = LazyModule(
    new ApbPeripheralWrapper(AddressSet.misaligned(0x20000000, 0x2000))
  )
  lperipheral.node := apbxbar

  override lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
    val peripheral = IO(new peripheral())
    peripheral <> lperipheral.module.peripheral

    // --- 实例化模块 ---
    val IFU = LazyIFU.module
    val IDU = Module(new riscv_soc.cpu.IDU)
    val ALU = Module(new riscv_soc.cpu.ALU)
    val AGU = Module(new riscv_soc.cpu.AGU)
    val LSU = LazyLSU.module
    val WBU = Module(new riscv_soc.cpu.WBU)
    val REG = Module(new riscv_soc.cpu.REG)
    val PipelineCtrl = Module(new bus.PipelineCtrl)
    
    CoreConnect(this)
  }
}

class jyd_core(idBits: Int)(implicit p: Parameters) extends LazyModule {
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
    val LazyIFU = LazyModule(new riscv_soc.cpu.IFU(idBits = idBits - 1))
    val LazyLSU = LazyModule(new riscv_soc.cpu.LSU(idBits = idBits - 1))

    val xbar_if = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
    val xbar_ls = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)

    xbar_if := LazyIFU.masterNode
    xbar_ls := LazyLSU.masterNode

    val beatBytes = 4
    val node_if = AXI4SlaveNode(
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

    val node_ls = AXI4SlaveNode(
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

    node_if := xbar_if
    node_ls := xbar_ls

    override lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
      val io = IO(new Bundle {
        val master_if = AXI4Bundle(CPUAXI4BundleParameters())
        val master_ls = AXI4Bundle(CPUAXI4BundleParameters())
      })

      io.master_if <> node_if.in(0)._1
      io.master_ls <> node_ls.in(0)._1

      val IFU = LazyIFU.module
      val IDU = Module(new riscv_soc.cpu.IDU)
      val ALU = Module(new riscv_soc.cpu.ALU)
      val AGU = Module(new riscv_soc.cpu.AGU)
      val LSU = LazyLSU.module
      val WBU = Module(new riscv_soc.cpu.WBU)
      val REG = Module(new riscv_soc.cpu.REG)
      val PipelineCtrl = Module(new bus.PipelineCtrl)
      
      CoreConnect(this)
    }
}

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

class top extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new jyd(idBits = ChipLinkParam.idBits))
    val mdut = Module(dut.module)

    val peripheral = IO(new peripheral())
    mdut.peripheral <> peripheral
    mdut.dontTouchPorts()
}

class core extends Module {
    val io = IO(new Bundle {
      val master_if = AXI4Bundle(CPUAXI4BundleParameters())
      val master_ls = AXI4Bundle(CPUAXI4BundleParameters())
    })

    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new jyd_core(idBits = ChipLinkParam.idBits))
    val mdut = Module(dut.module)

    mdut.dontTouchPorts()

    io.master_if <> mdut.io.master_if
    io.master_ls <> mdut.io.master_ls
}
