package nzea_core

import chisel3._
import chisel3.util.Valid
import nzea_rtl.{LiteBusRO, LiteBusRW}
import nzea_core.frontend.IbusUser
import nzea_core.backend.integer.LsuOp
import nzea_core.retire.CommitMsg
import nzea_config.core.{CoreConfig, EngineKind}

/** Top-level core IO contract, identical for every engine kind — the tile and the core-target wrapper only see this.
  *
  * ibus user payload carries the IFU epoch (IbusUser); dbus user payload the LSU request tags (rob_id/lsuOp/addr2/
  * p_rd/is_mmio). Both match the width formulas the two engines' internal bus units use.
  */
class CoreIo(addrWidth: Int)(implicit config: CoreConfig) extends Bundle {
  private val robIdWidth = chisel3.util.log2Ceil(config.robDepth.max(2))
  private val dbusUserWidth = addrWidth.max(robIdWidth + LsuOp.getWidth + 2 + config.prfAddrWidth + 1) // +1 is_mmio

  val ibus = new LiteBusRO(addrWidth, addrWidth, IbusUser.userWidth(addrWidth), 1)
  val dbus = new LiteBusRW(addrWidth, addrWidth, dbusUserWidth, 1)
  val commit_msg = Output(Valid(new CommitMsg))
}

/** Engine contract: any core that can stand in the tile (same ibus/dbus/commit_msg top-level ports). */
trait CoreLike extends Module {
  def io: CoreIo
}

/** Instantiate the core selected by [[CoreConfig.engine]]. The middle engine is the polymorphic point: both front-end
  * and back-end contracts stay fixed (see goal/core-baseline.md).
  */
object CoreEngine {

  def apply(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig): CoreLike =
    config.engine match {
      case EngineKind.InOrder => Module(new min.MinCore(mmioRanges))
      case EngineKind.OoO     => Module(new Core(mmioRanges))
    }

}
