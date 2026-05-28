package nzea_device

import chisel3._
import chisel3.util.{log2Ceil, Cat, Decoupled, MuxLookup}

/** Minimal UART transmitter (8N1).
  *
  * @param clockHz
  *   system clock in Hz
  * @param baudRate
  *   desired baud rate (e.g. 115200, 9600, 1000000)
  *
  * Uses a 32-bit DDS phase accumulator for fractional-N baud generation — exact average rate regardless of clock
  * frequency, jitter < 1 clock cycle. Fires `io.in` (Decoupled) to consume one byte per transmission.
  *
  * Phase is reset on each new byte so the start bit always spans a full baud period.
  */
class UartTx(clockHz: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val txd = Output(Bool())
    val in = Flipped(Decoupled(UInt(8.W)))
  })

  // ── Baud generation ───────────────────────────────────────
  object State extends ChiselEnum {
    val Idle, Shift = Value
  }

  import State._

  val state = RegInit(Idle)
  val shift = RegInit("b1111111111".U(10.W))
  val bitIdx = RegInit(0.U(4.W))

  val sending = io.in.valid && state === Idle

  // ── DDS baud tick (phase reset on new byte for full start bit) ─
  private val phaseBits = 32
  private val phaseScale = (1L << phaseBits)
  private val phaseInc: Long = ((baudRate.toLong * phaseScale) + clockHz / 2) / clockHz.toLong

  val phase = RegInit(0.U(phaseBits.W))
  val tickPrev = RegInit(false.B)
  val tick = !sending && tickPrev && !phase(phaseBits - 1)

  phase := Mux(sending, 0.U, phase + phaseInc.U)
  tickPrev := Mux(sending, false.B, phase(phaseBits - 1))

  val byteDone = tick && bitIdx === 10.U

  // ── FSM ────────────────────────────────────────────────────
  state := MuxLookup(state.asUInt, Idle)(
    Seq(
      Idle.asUInt -> Mux(sending, Shift, Idle),
      Shift.asUInt -> Mux(byteDone, Idle, Shift)
    )
  )

  io.in.ready := state === Idle

  when(sending) {
    shift := Cat(1.U(1.W), io.in.bits, 0.U(1.W))
    bitIdx := 0.U
  }

  when(state === Shift && tick) {
    when(!byteDone) {
      shift := Cat(1.U(1.W), shift(9, 1))
      bitIdx := bitIdx + 1.U
    }
  }

  io.txd := shift(0)
}
