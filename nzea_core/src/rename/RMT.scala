package nzea_core.rename

import chisel3._
import chisel3.util.{log2Ceil, Valid}

/** Rename Map Table: 31 entries for AR1~AR31. AR0 (x0) always maps to PR0, no RMT entry.
  * Checkpoint: updated on commit (rd->p_rd), restored on flush. Independent of branch dispatch.
  */
class RMT(depth: Int) extends Module {
  require(depth >= 32)
  private val addrWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val read = Vec(3, new Bundle {
      val ar   = Input(UInt(5.W))
      val pr   = Output(UInt(addrWidth.W))
    })
    val write = Input(Valid(new Bundle {
      val ar = UInt(5.W)
      val pr = UInt(addrWidth.W)
    }))
    val commit = Input(Valid(new Bundle {
      val rd_index = UInt(5.W)
      val p_rd     = UInt(addrWidth.W)
      val old_p_rd = UInt(addrWidth.W)
    }))
    val flush = Input(Bool())
  })

  // table(i) = mapping for AR(i+1); AR0 always -> PR0, no entry
  val table    = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(addrWidth.W))))
  val table_cp = RegInit(VecInit(Seq.tabulate(31)(i => (i + 1).U(addrWidth.W))))

  // Checkpoint: always update on commit (incl. flush) so cp stays in sync for next flush.
  // Reg write takes effect next cycle; same-cycle read of table_cp sees OLD value.
  // When freeing old_p_rd: any other table_cp entry pointing to it is stale (PR was incorrectly
  // shared). Reset those to initial mapping (AR i+1 -> PR i+1) to avoid pointing to freed PR.
  when(io.commit.valid && io.commit.bits.rd_index =/= 0.U) {
    table_cp(io.commit.bits.rd_index - 1.U) := io.commit.bits.p_rd
    when(io.commit.bits.old_p_rd =/= io.commit.bits.p_rd && io.commit.bits.old_p_rd =/= 0.U) {
      for (i <- 0 until 31) {
        when((i + 1).U =/= io.commit.bits.rd_index && table_cp(i) === io.commit.bits.old_p_rd) {
          table_cp(i) := (i + 1).U(addrWidth.W)
        }
      }
    }
  }
  when(io.flush) {
    // Restore from table_cp; apply old_p_rd invalidation combinationally (Reg update is next cycle).
    val freeOldPr = io.commit.valid && io.commit.bits.rd_index =/= 0.U &&
      io.commit.bits.old_p_rd =/= io.commit.bits.p_rd && io.commit.bits.old_p_rd =/= 0.U
    for (i <- 0 until 31) {
      val isStale = freeOldPr && (i + 1).U =/= io.commit.bits.rd_index &&
        table_cp(i) === io.commit.bits.old_p_rd
      table(i) := Mux(isStale, (i + 1).U(addrWidth.W), table_cp(i))
    }
    when(io.commit.valid && io.commit.bits.rd_index =/= 0.U) {
      table(io.commit.bits.rd_index - 1.U) := io.commit.bits.p_rd
    }
  }.elsewhen(io.write.valid && io.write.bits.ar =/= 0.U) {
    table(io.write.bits.ar - 1.U) := io.write.bits.pr
  }

  // Reg write takes effect next cycle; flush-cycle reads must see restore+commit combinationally.
  // When freeing old_p_rd, table_cp entries pointing to it are stale; use initial mapping (ar) for read.
  def readPr(ar: UInt): UInt = Mux(
    ar === 0.U,
    0.U(addrWidth.W),
    Mux(
      io.flush,
      Mux(
        io.commit.valid && io.commit.bits.rd_index === ar && io.commit.bits.rd_index =/= 0.U,
        io.commit.bits.p_rd,
        Mux(
          io.commit.valid && io.commit.bits.rd_index =/= 0.U &&
            io.commit.bits.old_p_rd =/= io.commit.bits.p_rd && io.commit.bits.old_p_rd =/= 0.U &&
            io.commit.bits.rd_index =/= ar && table_cp(ar - 1.U) === io.commit.bits.old_p_rd,
          ar.pad(addrWidth),
          table_cp(ar - 1.U)
        )
      ),
      table(ar - 1.U)
    )
  )
  io.read(0).pr := readPr(io.read(0).ar)
  io.read(1).pr := readPr(io.read(1).ar)
  io.read(2).pr := readPr(io.read(2).ar)
}
