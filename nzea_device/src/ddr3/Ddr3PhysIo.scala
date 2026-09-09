package nzea_device.ddr3

import chisel3._
import chisel3.experimental.Analog

/** DDR3 control pins — FPGA outputs toward the DDR3 chip.
  *
  * Direction convention: as seen from inside the chip looking toward the DDR3
  * chip; every field is an output. Shared at every hierarchy level that carries
  * the control group (MIG subsystem IO, board core IO, top-level physical pins)
  * so boundaries connect with a single bulk `:=` instead of per-pin wiring.
  *
  * The bidirectional data pins (dq/dqs) are NOT part of this bundle: they are
  * `Analog` and only exist at the physical-pin level ([[Ddr3PhysIo]]), because
  * mixing Analog with directional fields trips the firtool sink check in
  * intermediate modules.
  *
  * Port-name contract: at the board top the bundle is instantiated as `DDR3`,
  * yielding top-level ports `DDR3_addr`, `DDR3_ba`, ... — the names the DDR pin
  * XDC constrains by name. Do not rename fields without updating the XDC.
  */
class Ddr3CtrlIo extends Bundle {
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

/** DDR3 physical pin bundle — control outputs ([[Ddr3CtrlIo]]) plus the
  * bidirectional data pins, used at the board top level where the Analog pins
  * attach directly to the chip pads.
  *
  * The MIG system clock is NOT part of the memory pins; pass it separately
  * (see [[Ddr3Subsystem]].clk_200m).
  */
class Ddr3PhysIo extends Ddr3CtrlIo {
  val dq = Analog(16.W)
  val dqs_p = Analog(2.W)
  val dqs_n = Analog(2.W)
}
