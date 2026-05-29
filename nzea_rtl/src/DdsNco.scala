package nzea_rtl

import chisel3._
import chisel3.util.log2Ceil

/** Numerically-controlled oscillator (DDS phase accumulator).
  *
  * Generates a tick pulse at `tickHz` average frequency from a `clockHz` system clock. Uses a configurable-width phase
  * accumulator for fractional-N division — jitter < 1 clock cycle, average rate exact.
  *
  * @param clockHz
  *   system clock frequency in Hz
  * @param tickHz
  *   desired tick output frequency in Hz
  * @param phaseWidth
  *   accumulator width in bits (default 32; wider = finer resolution)
  * @param resetPhase
  *   when asserted, resets the phase accumulator to 0 (for synchronizing start of period)
  */
class DdsNco(clockHz: Int, tickHz: Int, phaseWidth: Int = 32) extends Module {
  val io = IO(new Bundle {
    val tick       = Output(Bool()) // asserted for 1 cycle on each overflow
    val resetPhase = Input(Bool())  // reset accumulator → next tick after full period
  })

  private val phaseScale  = (1L << phaseWidth)
  private val phaseInc: Long = ((tickHz.toLong * phaseScale) + clockHz / 2) / clockHz.toLong

  val phase    = RegInit(0.U(phaseWidth.W))
  val tickPrev = RegInit(false.B)

  io.tick := tickPrev && !phase(phaseWidth - 1)

  phase    := Mux(io.resetPhase, 0.U, phase + phaseInc.U)
  tickPrev := Mux(io.resetPhase, false.B, phase(phaseWidth - 1))
}
