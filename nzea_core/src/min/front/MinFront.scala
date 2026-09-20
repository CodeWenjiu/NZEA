package nzea_core.min.front

import chisel3._
import nzea_core.min.{CoreChannels, FrontBusPort}
import nzea_rtl.PipelineConnect
import nzea_config.core.CoreConfig

/** Front-end of the baseline core: fetch ([[MinIfu]]) + decode ([[MinIdu]]) in one module, with the internal pipeline
  * registers owned here.
  *
  * Bus contract with the interconnect: a registered stream of decoded instructions on `bus.decoded`, a redirect target
  * on `bus.redirect` (branch resolution flush), and the instruction bus on `bus.ibus`. The flush signal propagates back
  * through `bus.decoded.flush` (consumer-driven) into the IDU and IFU, exactly as when the stages were separate — this
  * module only draws the front-end boundary, it does not change timing.
  */
class MinFront(implicit config: CoreConfig) extends Module {
  private val width = config.width

  val ifu = Module(new MinIfu)
  val idu = Module(new MinIdu(width))

  val io = IO(new Bundle {
    val bus = new FrontBusPort(width)
    val ibus = CoreChannels.ibus(width)
  })

  // Internal pipeline: IFU output registers into the (combinational) IDU. The decoded stream stays combinational —
  // the IDU reads the ARF at decode, and that read must observe post-commit values. A registered decoded boundary
  // would capture the read two cycles before the middle issues (stale operands): with REG1 + REG2 the addi read of
  // its source happened before the previous commit's write landed. Keeping the boundary combinational makes the ARF
  // read happen at the issue capture cycle, which the middle's idle gate (!exValid && !wbValid) already guarantees
  // happens after every prior commit's write-back.
  PipelineConnect(ifu.io.out, idu.io.in)
  idu.io.out <> io.bus.decoded

  idu.io.arf_write := io.bus.arf_write
  ifu.io.redirect := io.bus.redirect
  io.ibus <> ifu.io.bus
}
