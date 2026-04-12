package nzea_core.backend.vector

import chisel3._
import nzea_rtl.PipeIO
import nzea_config.NzeaConfig

/** VIQ → [[VALU]] → [[VectorWbu]] → [[VectorPrf]]; for integration tests and future Core hookup. */
class VectorBackend(robIdWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val pvrAddrWidth = config.pvrAddrWidth

  val io = IO(new Bundle {
    val viq_in = Flipped(new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))
    val flush  = Input(Bool())
  })

  val viq  = Module(new VectorIssueQueue(robIdWidth))
  val valu = Module(new VALU(robIdWidth))
  val pvr  = Module(new VectorPrf(2, 1))
  val vwbu = Module(new VectorWbu())

  viq.io.in <> io.viq_in
  viq.io.toValu <> valu.io.in
  valu.io.out <> vwbu.io.in
  vwbu.io.flush := io.flush

  for (j <- 0 until 2) {
    pvr.io.read(j) <> viq.io.prf_read(j)
  }
  pvr.io.write(0) := vwbu.io.out
  pvr.io.allocClear.valid := false.B
  pvr.io.allocClear.bits  := 0.U
}
