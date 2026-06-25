package nzea_fpga.boards.lxb_artix7

import chisel3._

/** ILA IP BlackBox — instantiated in RTL, Vivado IP matches via desiredName.
  *
  * Each probe is a named port with dontTouch. The companion object holds the widths list that TCL generation reads.
  * When you add a probe, add the port here AND the width in [[IlaProbes.widths]].
  */
class IlaProbes extends ExtModule {
  override def desiredName = "u_ila_0"

  val clk = IO(Input(Clock()))
  val probe0 = IO(Input(Bool())); dontTouch(probe0) // calib_done
  val probe1 = IO(Input(Bool())); dontTouch(probe1) // app_rdy
  val probe2 = IO(Input(Bool())); dontTouch(probe2) // app_rd_data_valid
  val probe3 = IO(Input(Bool())); dontTouch(probe3) // app_wdf_rdy
  val probe4 = IO(Input(Bool())); dontTouch(probe4) // app_en
  val probe5 = IO(Input(UInt(3.W))); dontTouch(probe5) // app_cmd
  val probe6 = IO(Input(UInt(29.W))); dontTouch(probe6) // app_addr
  val probe7 = IO(Input(UInt(32.W))); dontTouch(probe7) // rd_data
  val probe8 = IO(Input(UInt(4.W))); dontTouch(probe8) // fsm
  val probe9 = IO(Input(Bool())); dontTouch(probe9) // pass
  val probe10 = IO(Input(UInt(5.W))); dontTouch(probe10) // testBits
}

object IlaProbes {
  val depth = 4096

  /** Must match the probe port order and widths in [[IlaProbes]]. */
  val widths: Seq[Int] = Seq(1, 1, 1, 1, 1, 3, 29, 32, 4, 1, 5)
}
