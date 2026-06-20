package nzea_fpga.boards.lxb_artix7

import chisel3._
import chisel3.util._

/** MMCM clock generator: 50 MHz → 200 MHz + 100 MHz.
  *
  * Implemented by Xilinx Clocking Wizard IP `clk_wiz_0` in Vivado.
  */
class Mmcm50to200 extends ExtModule {
  override def desiredName = "clk_wiz_0"

  val clk_in1 = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val locked = IO(Output(Bool()))
  val clk_out1 = IO(Output(Clock())) // 200 MHz
  val clk_out2 = IO(Output(Clock())) // 100 MHz
}
