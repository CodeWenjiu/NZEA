package nzea_core.backend.vector

import chisel3._
import chisel3.util.Valid
import nzea_core.PipeIO
import nzea_config.NzeaConfig

/** One-cycle delay for PVR writes (mirrors [[nzea_core.retire.WBU]]; enables future bypass from `io.out`). */
class VectorWbu(implicit config: NzeaConfig) extends Module {
  private val pvrAddrWidth = config.pvrAddrWidth

  val io = IO(new Bundle {
    val in    = Flipped(new PipeIO(new VrfWriteBundle(pvrAddrWidth)))
    val out   = Output(Valid(new VrfWriteBundle(pvrAddrWidth)))
    val flush = Input(Bool())
  })

  io.in.ready := true.B
  io.in.flush := io.flush

  val validNext = RegInit(false.B)
  val addrNext  = Reg(UInt(pvrAddrWidth.W))
  val dataNext  = Reg(UInt(32.W))

  when(io.in.flush) {
    validNext := false.B
  }.otherwise {
    validNext := io.in.valid
    addrNext  := io.in.bits.addr
    dataNext  := io.in.bits.data
  }

  io.out.valid       := validNext
  io.out.bits.addr := addrNext
  io.out.bits.data := dataNext
}
