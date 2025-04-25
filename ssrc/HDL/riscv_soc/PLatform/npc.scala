package riscv_soc.platform.npc

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

class top extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val dut = LazyModule(new riscv_soc.npc(idBits = ChipLinkParam.idBits))
  val mdut = Module(dut.module)
  mdut.dontTouchPorts()
}
