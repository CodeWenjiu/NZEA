package nzea_core.rename

import chisel3._
import chisel3.util.{log2Ceil, Valid}

/** Rename Map Table: 32 entries, each maps AR index to physical register.
  * Supports checkpoint for flush recovery.
  */
class RMT(depth: Int) extends Module {
  require(depth >= 32)
  private val addrWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val read = Vec(2, new Bundle {
      val ar   = Input(UInt(5.W))
      val pr   = Output(UInt(addrWidth.W))
    })
    val write = Input(Valid(new Bundle {
      val ar = UInt(5.W)
      val pr = UInt(addrWidth.W)
    }))
    val checkpoint = new Bundle {
      val snapshot = Input(Bool())
      val restore  = Input(Bool())
    }
  })

  val table       = RegInit(VecInit(Seq.tabulate(32)(i => i.U(addrWidth.W))))
  val table_cp    = Reg(Vec(32, UInt(addrWidth.W)))

  when(io.checkpoint.snapshot) {
    for (i <- 0 until 32) { table_cp(i) := table(i) }
  }
  when(io.checkpoint.restore) {
    for (i <- 0 until 32) { table(i) := table_cp(i) }
  }.elsewhen(io.write.valid && io.write.bits.ar =/= 0.U) {
    table(io.write.bits.ar) := io.write.bits.pr
  }

  io.read(0).pr := table(io.read(0).ar)
  io.read(1).pr := table(io.read(1).ar)
}
