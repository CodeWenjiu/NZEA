package nzea_core

import chisel3._
import nzea_core.frontend.CsrType
import nzea_config.core.{CoreConfig, PayloadSpec}

/** Config-derived payload fields: one factory per [[PayloadSpec.Unit]].
  *
  * Every data-path bundle that carries an information unit (the RAS call/ret chain, ...) declares its fields through
  * these factories instead of hand-writing `if (PayloadSpec.enabled(...)) Some(...) else None` — the unit's existence
  * rule lives in one place, and the Option shape (absent field = structurally gone port/register) is guaranteed
  * identically everywhere. The unit must be chosen per data-path role: `retCommit` for the commit chain (RAS pop),
  * `retExec` for the execution chain (ret classification / BRU stats), `callExec*` for the RAS push classification.
  *
  * New unit: add it to [[PayloadSpec]] + one factory here + embed the field into the bundles that carry it.
  * This includes the back→middle report bundle (MinExecOut) — mandatory commit/redirect fields stay literal, units are
  * added through these factories.
  */
object PayloadFields {

  def retCommit(implicit c: CoreConfig): Option[Bool] =
    if (PayloadSpec.enabled(PayloadSpec.RetCommit)) Some(Bool()) else None

  def retExec(implicit c: CoreConfig): Option[Bool] =
    if (PayloadSpec.enabled(PayloadSpec.RetExec)) Some(Bool()) else None

  def callExecIsCall(implicit c: CoreConfig): Option[Bool] =
    if (PayloadSpec.enabled(PayloadSpec.CallExec)) Some(Bool()) else None

  def callExecRdIndex(implicit c: CoreConfig): Option[UInt] =
    if (PayloadSpec.enabled(PayloadSpec.CallExec)) Some(UInt(5.W)) else None

  // -------- Baseline report fields (back → middle, MinExecOut) --------
  //
  // Default transport content of the in-order engine's completion report: fields every report carries today are
  // declared here rather than literally in the bundle, so the "which fields exist" decision lives with the other
  // payload units. A future microarchitecture that no longer needs a piece of this report moves that factory to a
  // config-gated Option shape (like the units above) and updates one field type in the bundle — the report contract
  // itself never hand-writes existence rules.
  def reportDone: Bool = Bool()
  def reportData: UInt = UInt(32.W)
  def reportNextPc(width: Int): UInt = UInt(width.W)
  def reportCsrType: CsrType.Type = CsrType()
  def reportCsrData: UInt = UInt(32.W)
  def reportIsMmio: Bool = Bool()
  def reportFlush: Bool = Bool()
  def reportRedirectTarget(width: Int): UInt = UInt(width.W)

}