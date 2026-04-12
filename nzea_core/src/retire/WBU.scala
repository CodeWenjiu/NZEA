package nzea_core.retire

import chisel3._
import chisel3.util.Valid
import nzea_rtl.PipeIO
import nzea_core.frontend.PrfWriteBundle
import nzea_config.{FuConfig, NzeaConfig}

/** Write-Back Unit: 1-cycle delay for PRF writes. All FU + MemUnit outputs go through WBU.
  * Provides two-level bypass: Level 1 = inputs (FU output), Level 2 = outputs (delayed).
  * Flush: input from Commit, forwarded to FUs via PipeIO (io.in.flush).
  */
class WBU(prfAddrWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val numPorts = FuConfig.numPrfWritePorts

  val io = IO(new Bundle {
    val in  = Vec(numPorts, Flipped(new PipeIO(new PrfWriteBundle(prfAddrWidth))))
    val out = Output(Vec(numPorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    val flush = Input(Bool())
  })

  for (i <- 0 until numPorts) {
    io.in(i).ready := true.B
    io.in(i).flush := io.flush
  }

  for (i <- 0 until numPorts) {
    val validNext = RegInit(false.B)
    val addrNext  = Reg(UInt(prfAddrWidth.W))
    val dataNext  = Reg(UInt(32.W))

    when(io.in(i).flush) {
      validNext := false.B
    }.otherwise {
      validNext := io.in(i).valid
      addrNext  := io.in(i).bits.addr
      dataNext  := io.in(i).bits.data
    }

    io.out(i).valid := validNext
    io.out(i).bits.addr := addrNext
    io.out(i).bits.data := dataNext
  }
}
