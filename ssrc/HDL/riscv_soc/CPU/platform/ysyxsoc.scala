package riscv_soc.platform.ysyxsoc

import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

import config._
import riscv_soc.bus._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

import sifive._

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