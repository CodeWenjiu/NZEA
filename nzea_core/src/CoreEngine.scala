package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRO, LiteBusRW}
import nzea_core.front.IbusUser
import nzea_core.back.LsuOp
import nzea_config.core.CoreConfig

/** Width sources for the shared interface fields that used to be sized from removed microarchitecture config.
  *
  * The shared FU/MemUnit interfaces carry an in-flight id (`tag`) and a register-write index (`waddr`); the in-order
  * engine uses the id as the LSU request tag and ties the register index to zero. The values live here (single source)
  * so the core IO, the execution backend and the tile's fabric width formula stay in lockstep.
  */
object CoreTagWidths {

  /** LSU request-tag width: rides the `tag` echo of the shared MemUnit interface, matching responses to requests. */
  val lsuTag = 2

  /** Register-index field width of the shared FU write-back interfaces (constant 0: no renaming on this engine). */
  val regIndex = 1

  /** Dbus user-payload width: tag + lsuOp + addr2 + register index + is_mmio, at least `addrWidth`. */
  def dbusUserWidth(addrWidth: Int): Int =
    addrWidth.max(lsuTag + LsuOp.getWidth + 2 + regIndex + 1) // +1 is_mmio

}

/** Top-level core IO contract, identical for every engine kind — the tile and the core-target wrapper only see this.
  *
  * ibus user payload carries the IFU epoch (IbusUser); dbus user payload the LSU request tags.
  */
class CoreIo(addrWidth: Int)(implicit config: CoreConfig) extends Bundle {
  val ibus = new LiteBusRO(addrWidth, addrWidth, IbusUser.userWidth(addrWidth), 1)
  val dbus = new LiteBusRW(addrWidth, addrWidth, CoreTagWidths.dbusUserWidth(addrWidth), 1)
  val commit_msg = Output(Valid(new CommitMsg))
}

/** Engine contract: any core that can stand in the tile (same ibus/dbus/commit_msg top-level ports). */
trait CoreLike extends Module {
  def io: CoreIo
}

/** Instantiate the baseline core. The middle engine is the polymorphic point: alternative engines (OoO, vector) are
  * added back here as they return.
  */
object CoreEngine {

  def apply(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig): CoreLike =
    Module(new Core(mmioRanges))

}