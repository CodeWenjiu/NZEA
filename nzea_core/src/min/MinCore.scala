package nzea_core.min

import chisel3._
import nzea_core.{CoreIo, CoreLike}
import nzea_core.min.back.MinExec
import nzea_core.min.front.MinFront
import nzea_core.min.middle.MinMiddle
import nzea_config.core.CoreConfig

/** Baseline core: in-order single-issue, no branch prediction, no renaming.
  *
  * Same external contract as [[nzea_core.Core]] — ibus / dbus / commit_msg — so it can replace the OoO core in the tile
  * without touching the fabric. Three explicit stages, all traffic flowing through the unified [[CoreBus]]: front-end
  * ([[MinFront]]: fetch + decode with the internal pipeline), middle ([[MinMiddle]]: single-flight dispatch + ARF +
  * commit — the polymorphic engine point, replaced by an OoO engine later) and execute backend ([[MinExec]]: own
  * sequential dispatch over the shared FU implementations + MemUnit). All reuse the shared decode tables and execution
  * units.
  *
  * The core never wires individual stage channels — [[CoreInterconnect]] pairs the producer/consumer ends inside the
  * bus, and this module only mounts the three stages and lifts the external buses (ibus/dbus/commit) to the core IO
  * contract.
  */
class MinCore(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module with CoreLike {
  private val addrWidth = config.width

  val front = Module(new MinFront)
  val middle = Module(new MinMiddle)
  val back = Module(new MinExec(mmioRanges))
  val bus = Module(new CoreInterconnect(addrWidth))

  override val io: CoreIo = IO(new CoreIo(addrWidth))

  front.io.bus <> bus.io.front
  middle.io.bus <> bus.io.middle
  back.io.bus <> bus.io.back

  io.ibus <> front.io.ibus
  io.dbus <> back.io.dbus
  io.commit_msg := bus.io.middle.commit
}
