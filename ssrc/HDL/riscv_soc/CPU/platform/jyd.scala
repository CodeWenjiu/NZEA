package riscv_soc.platform.jyd

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

import config._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.CPUAXI4BundleParameters
import scopt.platform

class top extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val dut = LazyModule(new riscv_soc.platform.jyd.jyd(idBits = riscv_soc.ChipLinkParam.idBits))
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

  val dut = LazyModule(new riscv_soc.platform.jyd.jyd_core(idBits = riscv_soc.ChipLinkParam.idBits))
  val mdut = Module(dut.module)

  mdut.dontTouchPorts()

  io.master_if <> mdut.io.master_if
  io.master_ls <> mdut.io.master_ls
}
