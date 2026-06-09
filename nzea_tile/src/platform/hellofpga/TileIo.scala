package nzea_tile.platform.hellofpga

import chisel3._
import chisel3.util.Valid
import nzea_core.retire.CommitMsg
import nzea_device.uart.UartIo
import nzea_tile.platform.HasCommitMsg

class TileIo extends Bundle with HasCommitMsg {
  val commit_msg = Output(Valid(new CommitMsg))
  val fpga_uart = new UartIo
  val fpga_finish = Output(Bool())
}
