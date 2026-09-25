package nzea_core

import chisel3._
import nzea_core.{CoreIo, CoreLike}
import nzea_core.back.Back
import nzea_core.front.Front
import nzea_core.middle.Middle
import nzea_core.stats.CoreStats
import nzea_rtl.StatsRegs
import nzea_config.core.CoreConfig

/** Baseline core: in-order single-issue, no branch prediction, no renaming. riscv32i baseline — the M-extension units
  * are the STA critical chain on this engine and stay unexercised (the hasM-gated instances in [[back.int.IntPath]]
  * remain for the OoO engine).
  *
  * Same external contract as [[nzea_core.Core]] (ibus/dbus/commit_msg), three stages over the unified [[CoreBus]]:
  * [[Front]] (fetch + decode), [[Middle]] (single-flight dispatch + commit, the polymorphic engine point) and [[Back]]
  * (own dispatch over the shared FUs).
  */
class Core(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module with CoreLike {
  private val addrWidth = config.width

  val front = Module(new Front)
  val middle = Module(new Middle)
  val back = Module(new Back(mmioRanges))
  val bus = Module(new CoreInterconnect(addrWidth))

  override val io: CoreIo = IO(new CoreIo(addrWidth))

  front.io.bus <> bus.io.front
  middle.io.bus <> bus.io.middle
  back.io.bus <> bus.io.back

  io.ibus <> front.io.ibus
  io.dbus <> back.io.dbus
  io.commit_msg := bus.io.middle.commit

  // Simulation-only VPI counters (declared in CoreStats, zero synthesis cost).
  if (config.sim) {
    val stats = Module(new StatsRegs("CoreStats", CoreStats.coreCounters))
    stats.clock := clock
    stats.reset := reset
    stats.ports(CoreStats.cycle).en := true.B
    stats.ports(CoreStats.cycle).data := stats.ports(CoreStats.cycle).value + 1.U
    stats.ports(CoreStats.instCommit).en := io.commit_msg.valid
    stats.ports(CoreStats.instCommit).data := stats.ports(CoreStats.instCommit).value + 1.U
  }

}
