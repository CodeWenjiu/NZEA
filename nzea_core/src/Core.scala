package nzea_core

import chisel3._
import nzea_config.NzeaConfig

/** Core module: builds bus from config, passes to IFU; exposes IFU memory bus. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ifuBusGen = () => new CoreBusReadOnly(addrWidth, dataWidth)

  val io = IO(new Bundle {
    val bus = ifuBusGen()
  })

  val ifu = Module(new frontend.IFU(ifuBusGen, config.defaultPc))

  io.bus <> ifu.io.bus

  ifu.io.inst.ready := true.B
}
