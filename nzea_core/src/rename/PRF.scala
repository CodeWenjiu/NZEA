package nzea_core.rename

import chisel3._
import chisel3.util.{log2Ceil, Valid}

/** Physical Register File: storage + ready bits.
  * - Read: addr -> data, ready
  * - Write: addr, data, set ready
  * - Clear ready: on allocate (external)
  */
class PRF(depth: Int, dataWidth: Int = 32) extends Module {
  require(depth >= 32, "PRF depth must >= 32")
  private val addrWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val read = Vec(2, new Bundle {
      val addr  = Input(UInt(addrWidth.W))
      val data  = Output(UInt(dataWidth.W))
      val ready = Output(Bool())
    })
    val write = Input(Valid(new Bundle {
      val addr  = UInt(addrWidth.W)
      val data  = UInt(dataWidth.W)
      val setReady = Bool()
    }))
    val clearReady = Input(Valid(UInt(addrWidth.W)))
  })

  val regs   = RegInit(VecInit(Seq.fill(depth)(0.U(dataWidth.W))))
  val ready  = RegInit(VecInit(Seq.tabulate(depth)(i => (i < 32).B)))

  when(io.write.valid) {
    regs(io.write.bits.addr) := io.write.bits.data
    when(io.write.bits.setReady) {
      ready(io.write.bits.addr) := true.B
    }
  }
  when(io.clearReady.valid && io.clearReady.bits =/= 0.U) {
    ready(io.clearReady.bits) := false.B
  }

  io.read(0).data  := Mux(io.read(0).addr === 0.U, 0.U(dataWidth.W), regs(io.read(0).addr))
  io.read(0).ready := ready(io.read(0).addr)
  io.read(1).data  := Mux(io.read(1).addr === 0.U, 0.U(dataWidth.W), regs(io.read(1).addr))
  io.read(1).ready := ready(io.read(1).addr)
}
