package config

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet

object Cache_Param {
  var address: Seq[AddressSet] = Seq.empty
  var way: Int = 0
  var set: Int = 0
  var block_size: Int = 0
}

object Config {
  var Reset_Vector = "h80000000".U(32.W)

  var Simulate: Boolean = false

  var diff_mis_map: Seq[AddressSet] = AddressSet.misaligned(0, 0)

  var axi_fix: Boolean = false // for jyd remote

  def setResetVector(addr: UInt): Unit = {
    Reset_Vector := addr
  }

  def setSimulate(on: Boolean): Unit = {
    Simulate = on
  }

  def setDiffMisMap(addr: Seq[AddressSet]): Any = {
    diff_mis_map = addr
  }

  def setAxiFix(on: Boolean): Unit = {
    axi_fix = on
  }

  var Icache_Param: Option[(Seq[AddressSet], Int, Int, Int)] = None

  def setIcacheParam(address: Seq[AddressSet], set: Int, way: Int, block_size: Int): Unit = {
    Icache_Param match {
      case Some(_) =>
        throw new Exception("Icache_Param has already been set.")
      case None =>
        Cache_Param.address = address
        Cache_Param.set = set
        Cache_Param.way = way
        Cache_Param.block_size = block_size
        Icache_Param = Some((address, set, way, block_size))
    }
  }
}
