package config

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet

object Config {
  var Reset_Vector = "h80000000".U(32.W)

  var Simulate: Boolean = false

  var diff_mis_map: Seq[AddressSet] = AddressSet.misaligned(0, 0)

  def setResetVector(addr: UInt): Unit = {
    Reset_Vector := addr
  }

  def setSimulate(on: Boolean): Unit = {
    Simulate = on
  }

  def setDiffMisMap(addr: Seq[AddressSet]): Any = {
    diff_mis_map = addr
  }

  object Icache_Param {
    var address = AddressSet.misaligned(0x80000000L, 0x8000000)
    var way = 1
    var set = 32
    var block_size = 4
  }

  def setIcacheParam(address: Seq[AddressSet], set: Int, way: Int, block_size: Int): Unit = {
    Icache_Param.address = address
    Icache_Param.way = way
    Icache_Param.set = set
    Icache_Param.block_size = block_size
  }
}
