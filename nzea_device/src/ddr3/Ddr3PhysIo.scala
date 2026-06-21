package nzea_device.ddr3

import chisel3._
import chisel3.experimental.Analog

/** DDR3 physical pin bundle — reused at every hierarchy level.
  *
  * Direction convention: as seen from inside the chip looking toward the DDR3 chip.
  *   - clk_200m: clock input from board (MMCM in LxbArtix7Top)
  *   - dq/dqs: bidirectional Analog
  *   - addr/ba/ras/cas/we/ck/cke/odt/reset/dm: outputs toward DDR3
  */
class Ddr3PhysIo extends Bundle {
  val clk_200m = Input(Clock())
  val dq = Analog(16.W)
  val dqs_p = Analog(2.W)
  val dqs_n = Analog(2.W)
  val addr = Output(UInt(15.W))
  val ba = Output(UInt(3.W))
  val ras_n = Output(Bool())
  val cas_n = Output(Bool())
  val we_n = Output(Bool())
  val ck_p = Output(UInt(1.W))
  val ck_n = Output(UInt(1.W))
  val cke = Output(UInt(1.W))
  val odt = Output(UInt(1.W))
  val reset_n = Output(Bool())
  val dm = Output(UInt(2.W))
}
