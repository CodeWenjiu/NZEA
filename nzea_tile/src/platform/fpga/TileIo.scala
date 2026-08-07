package nzea_tile.platform.fpga

import chisel3._
import chisel3.util.Valid
import nzea_core.config.CoreConfig
import nzea_core.retire.CommitMsg
import nzea_device.uart.UartIo
import nzea_rtl.{BootReq, LiteBusRW}
import nzea_tile.platform.HasCommitMsg

class TileIo(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int
)(implicit config: CoreConfig)
    extends Bundle
    with HasCommitMsg {
  val commit_msg = Output(Valid(new CommitMsg))
  val fpga_uart = new UartIo
  val fpga_finish = Output(Bool())

  // Generic external RAM interface — board instantiates SRAM or DDR3 adapter
  val extRamBus = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val extRamBoot = Output(Valid(new BootReq(15)))
  val extRamCalibDone = Input(Bool())
}
