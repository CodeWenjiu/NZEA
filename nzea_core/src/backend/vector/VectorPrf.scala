package nzea_core.backend.vector

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.PrfReadIO
import nzea_config.CoreConfig

/** Physical vector register file: `vrfDepth` × 32-bit lanes with per-reg ready (RVV scaffold). */
class VectorPrf(numReadPorts: Int, numWritePorts: Int)(implicit config: CoreConfig) extends Module {
  private val pvrAddrWidth = config.pvrAddrWidth
  private val vrfDepth     = config.vrfDepth

  val io = IO(new Bundle {
    val write      = Input(Vec(numWritePorts, Valid(new VrfWriteBundle(pvrAddrWidth))))
    val allocClear = Input(Valid(UInt(pvrAddrWidth.W)))
    /** VIQ drives `addr` (Flipped [[frontend.PrfReadIO]] = regfile view). */
    val read = Vec(numReadPorts, Flipped(new PrfReadIO(pvrAddrWidth)))
  })

  val regs = RegInit(VecInit(Seq.fill(vrfDepth)(0.U(32.W))))

  for (w <- 0 until numWritePorts) {
    when(io.write(w).valid && io.write(w).bits.addr =/= 0.U) {
      val a = io.write(w).bits.addr
      regs(a) := io.write(w).bits.data
    }
  }

  for (r <- 0 until numReadPorts) {
    val a = io.read(r).addr
    io.read(r).data := Mux(a === 0.U, 0.U(32.W), regs(a))
  }
}
