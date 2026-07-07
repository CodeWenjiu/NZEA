package nzea_tile.platform.yosys

import chisel3._
import chisel3.util.Valid
import nzea_core.retire.CommitMsg
import nzea_rtl.FabricBusRW

class DeviceBusBundle(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val ram = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val uart16550 = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val sifive_test_finisher = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val clint = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)

  /** Ordered port list matching crossbar output indices. */
  def ports: Seq[FabricBusRW] = Seq(ram, uart16550, sifive_test_finisher, clint)

  /** Target device frequency (Hz). `None` = passthrough, no injected latency. */
  def devHz: Seq[Option[Double]] = Seq(Some(100e6), None, None, None)
}

import nzea_tile.platform.HasCommitMsg

class TileIo(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle with HasCommitMsg {
  val commit_msg = Output(Valid(new CommitMsg))
  val yosys_devices = new DeviceBusBundle(addrWidth, dataWidth, userWidth, idWidth)
}
