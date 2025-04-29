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

class apb_peripheral extends BlackBox {
  val io = IO(new Bundle {
    val Pclk = Input(Clock())
    val Prst = Input(Bool())
    val Paddr = Input(UInt(32.W))
    val Pwrite = Input(Bool())
    val Psel = Input(Bool())
    val Penable = Input(Bool())
    val Pwdata = Input(UInt(32.W))
    val Pstrb = Input(UInt(4.W))
    
    val Prdata = Output(UInt(32.W))
    val Pready = Output(Bool())
    val Pslverr = Output(Bool())

    val LED = Output(UInt(32.W))
    val SW1 = Input(UInt(32.W))
    val SW2 = Input(UInt(32.W))
    val SEG = Output(UInt(32.W))
  })
}

class display_seg extends BlackBox {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val s = Input(UInt(32.W))
    val seg1 = Input(UInt(7.W))
    val seg2 = Input(UInt(7.W))
    val seg3 = Input(UInt(7.W))
    val seg4 = Input(UInt(7.W))
    val ans = Input(UInt(8.W))
  })
}

class peripheral extends Bundle {
  val LED = Output(UInt(32.W))
  val SW1 = Input(UInt(32.W))
  val SW2 = Input(UInt(32.W))
  val SEG = Output(UInt(32.W))
}

class ApbPeripheralWrapper(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val beatBytes = 4
  val node = APBSlaveNode(Seq(APBSlavePortParameters(
    Seq(APBSlaveParameters(
      address       = address,
      executable    = true,
      supportsRead  = true,
      supportsWrite = true)
    ),
    beatBytes  = beatBytes)))
      
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val APB = node.in(0)._1

    val apb_peripheral_0 = Module(new apb_peripheral())
    apb_peripheral_0.io.Pclk := clock
    apb_peripheral_0.io.Prst := reset

    apb_peripheral_0.io.Paddr <> APB.paddr
    apb_peripheral_0.io.Pwrite <> APB.pwrite
    apb_peripheral_0.io.Psel <> APB.psel
    apb_peripheral_0.io.Penable <> APB.penable
    apb_peripheral_0.io.Pwdata <> APB.pwdata
    apb_peripheral_0.io.Pstrb <> APB.pstrb
    
    APB.prdata <> apb_peripheral_0.io.Prdata
    APB.pready <> apb_peripheral_0.io.Pready
    APB.pslverr <> apb_peripheral_0.io.Pslverr

    val peripheral = IO(new peripheral())
    peripheral.SW1 <> apb_peripheral_0.io.SW1
    peripheral.SW2 <> apb_peripheral_0.io.SW2
    peripheral.SEG <> apb_peripheral_0.io.SEG
    peripheral.LED <> apb_peripheral_0.io.LED
  }
}
