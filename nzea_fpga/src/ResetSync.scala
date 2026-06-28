package nzea_fpga

import chisel3._
import chisel3.util._

/** Two-stage synchronizer for an asynchronous external reset.
  *
  * Produces a metastability-safe reset in `clock` domain, same polarity as `raw`.
  *
  * @param raw
  *   raw reset pin
  * @param activeHigh
  *   whether `raw` is active-high
  * @param clock
  *   target clock for sync registers
  */
object ResetSync {

  def apply(raw: Bool, activeHigh: Boolean, clock: Clock): Bool = {
    val init = (!activeHigh).B // power-on / non-reset value
    withClockAndReset(clock, false.B) {
      RegNext(RegNext(raw, init), init)
    }
  }

}
