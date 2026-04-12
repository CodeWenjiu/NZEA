package nzea_tile

import chisel3._
import chisel3.util.Valid
import nzea_config.NzeaConfig
import nzea_core.dpi.{CommitDpiBridge, DbusDpiBridge, IbusDpiBridge}
import nzea_core.retire.CommitMsg

/** Tile-level control and status hooks. Inputs must be driven by the SoC/testbench (tie safe defaults in synthesis). */
class TileControlBundle extends Bundle {
  /** Reserved: active-low external core reset qualifier (1 = normal). Not yet wired into Core. */
  val ext_reset_n = Input(Bool())
  /** Reserved: active-high external flush into tile. Not yet OR-ed into core flush. */
  val ext_flush_req = Input(Bool())
}

/** Tile-level observability (optional taps). */
class TileStatusBundle extends Bundle {
  /** High while tile exposes a valid commit message on the core tap (same as core). */
  val commit_msg_valid = Output(Bool())
}

/** CPU tile: [[nzea_core.Core]] plus explicit bus routing insertion points and tile control IO.
  * Bus fabric is currently pass-through (`ibusRouter` / `dbusRouter`); replace with arbiters/mux later.
  * `config.sim` mirrors [[nzea_core.CoreElaborate.Top]] (DPI vs exposed ibus/dbus/commit).
  */
class NzeaTile(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core = Module(new nzea_core.Core)

  val ibusRouter = Wire(chiselTypeOf(core.io.ibus))
  val dbusRouter = Wire(chiselTypeOf(core.io.dbus))
  ibusRouter <> core.io.ibus
  dbusRouter <> core.io.dbus

  val ctrl   = IO(new TileControlBundle)
  val status = IO(new TileStatusBundle)
  if (config.sim) {
    val ib = Module(new IbusDpiBridge(addrWidth, dataWidth, core.io.ibus.userWidth))
    val db = Module(new DbusDpiBridge(addrWidth, dataWidth, core.io.dbus.userWidth))
    val cb = Module(new CommitDpiBridge)
    ib.io.bus <> ibusRouter
    db.io.bus <> dbusRouter
    cb.io.commit_msg := core.io.commit_msg
    status.commit_msg_valid := core.io.commit_msg.valid
  } else {
    val ibus = IO(chiselTypeOf(ibusRouter))
    val dbus = IO(chiselTypeOf(dbusRouter))
    val commit_msg = IO(Output(Valid(new CommitMsg)))
    ibus   <> ibusRouter
    dbus   <> dbusRouter
    commit_msg := core.io.commit_msg
    status.commit_msg_valid := core.io.commit_msg.valid
  }

  // Reserved: connect ctrl to Core reset / flush when tile policy is defined.
}
