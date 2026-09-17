package nzea_core.min

import chisel3._
import chisel3.util.Valid
import nzea_rtl.PipelineConnect
import nzea_core.retire.CommitMsg
import nzea_config.core.CoreConfig

/** Baseline core: in-order single-issue, no branch prediction, no renaming.
  *
  * Same external contract as [[nzea_core.Core]] — ibus / dbus / commit_msg — so it can replace the OoO core in the tile
  * without touching the fabric. Front-end (MinIfu), middle (MinIdu) and execute/commit (MinEx) all reuse the shared
  * Backend FU cluster and MemUnit; the middle stage is the future polymorphic point (dispatch vs reorder).
  */
class MinCore(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width

  val ifu = Module(new MinIfu)
  val idu = Module(new MinIdu(addrWidth))
  val ex = Module(new MinEx(mmioRanges))

  val io = IO(new Bundle {
    val ibus = chiselTypeOf(ifu.io.bus)
    val dbus = chiselTypeOf(ex.io.dbus)
    val commit_msg = Output(Valid(new CommitMsg))
  })

  PipelineConnect(ifu.io.out, idu.io.in)
  PipelineConnect(idu.io.out, ex.io.in)

  ifu.io.redirect_pc := ex.io.redirect.bits

  io.ibus <> ifu.io.bus
  io.dbus <> ex.io.dbus
  io.commit_msg := ex.io.commit_msg
}
