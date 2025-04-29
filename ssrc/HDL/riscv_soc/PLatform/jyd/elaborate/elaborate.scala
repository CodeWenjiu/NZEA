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

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

class top extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new riscv_soc.platform.jyd.jyd(idBits = ChipLinkParam.idBits))
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

    val dut = LazyModule(new riscv_soc.platform.jyd.jyd_core(idBits = ChipLinkParam.idBits))
    val mdut = Module(dut.module)

    mdut.dontTouchPorts()

    io.master_if <> mdut.io.master_if
    io.master_ls <> mdut.io.master_ls
}

class jyd_remote_core extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM
    val DRAM = new DRAM
  })

  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val mdut = Module(new riscv_soc.platform.jyd.jyd_remote_cpu)

  io.IROM <> mdut.io.IROM
  io.DRAM <> mdut.io.DRAM
}
