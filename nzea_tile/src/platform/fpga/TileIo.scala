package nzea_tile.platform.fpga

import chisel3._
import chisel3.util.Valid
import nzea_config.core.CoreConfig
import nzea_core.retire.CommitMsg
import nzea_device.uart.UartIo
import nzea_rtl.{BootReq, LiteBusRW}
import nzea_tile.platform.HasCommitMsg

/** Generic read-only debug observation port — the tile exposes internal state
  * upward; instrumentation (ILA etc.) lives in the board layer above.
  */
class DebugIo extends Bundle {
  /** Root of the tile-internal reset tree (tileReset || bootFsm cpu_reset) —
    * carries the uart-reset event, invisible at the board top otherwise.
    */
  val cpuReset = Bool()
}

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
  val debug = Output(new DebugIo)

  // Generic external RAM interface — board instantiates SRAM or DDR3 adapter
  val extRamBus = new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val extRamBoot = Output(Valid(new BootReq(15)))
  val extRamCalibDone = Input(Bool())
}
