package nzea_core.front

import chisel3._
import nzea_core.{CoreChannels, FrontBusPort}
import nzea_rtl.PipelineConnect
import nzea_config.core.CoreConfig

/** Front-end: fetch ([[Ifu]]) + decode ([[Idu]]) in one module, with the internal pipeline registers owned here.
  *
  * The decoded stream onto the boundary stays combinational: the IDU reads the ARF at decode and the middle's idle gate
  * guarantees that read happens after every prior commit's write-back. A registered boundary would capture the read
  * cycles before issue and hand the middle stale operands.
  */
class Front(implicit config: CoreConfig) extends Module {
  private val width = config.width

  val ifu = Module(new Ifu)
  val idu = Module(new Idu(width))

  val io = IO(new Bundle {
    val bus = new FrontBusPort(width)
    val ibus = CoreChannels.ibus(width)
  })

  PipelineConnect(ifu.io.out, idu.io.in)
  idu.io.out <> io.bus.decoded

  idu.io.arf_write := io.bus.arf_write
  ifu.io.redirect := io.bus.redirect
  io.ibus <> ifu.io.bus
}