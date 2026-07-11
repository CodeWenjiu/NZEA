package nzea_tile.platform.yosys

import chisel3._
import chisel3.util.Valid
import nzea_core.retire.CommitMsg
import nzea_rtl.LiteBusRW

class DeviceBusBundle(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val ram = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val uart16550 = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val sifive_test_finisher = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val clint = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)

  /** Ordered port list matching crossbar output indices. */
  def ports: Seq[LiteBusRW] = Seq(ram, uart16550, sifive_test_finisher, clint)

  /** Target device frequency (Hz). `None` = passthrough, no injected latency. */
  def devHz: Seq[Option[Double]] = Seq(Some(400e6), Some(100e6), Some(100e6), Some(100e6))
}

import nzea_tile.platform.HasCommitMsg

class TileIo(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle with HasCommitMsg {
  val commit_msg = Output(Valid(new CommitMsg))
  val yosys_devices = new DeviceBusBundle(addrWidth, dataWidth, userWidth, idWidth)
}
