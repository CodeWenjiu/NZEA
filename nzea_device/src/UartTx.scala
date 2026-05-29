package nzea_device

import chisel3._
import chisel3.util.{Cat, Decoupled, MuxLookup}
import nzea_rtl.DdsNco

/** Minimal UART transmitter (8N1).
  *
  * @param clockHz  system clock in Hz
  * @param baudRate desired baud rate (e.g. 115200, 9600, 1000000)
  *
  * Fires `io.in` (Decoupled) to consume one byte per transmission. Uses [[DdsNco]] for fractional-N baud generation.
  */
class UartTx(clockHz: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val txd = Output(Bool())
    val in  = Flipped(Decoupled(UInt(8.W)))
  })

  // ── State ─────────────────────────────────────────────────
  object State extends ChiselEnum {
    val Idle, Shift = Value
  }
  import State._

  val state   = RegInit(Idle)
  val shift   = RegInit("b1111111111".U(10.W))
  val bitIdx  = RegInit(0.U(4.W))

  val sending  = io.in.valid && state === Idle

  // ── Baud tick via DDS ─────────────────────────────────────
  val dds = Module(new DdsNco(clockHz, baudRate))
  dds.io.resetPhase := sending  // keep start bit at full baud period
  val tick = !sending && dds.io.tick

  val byteDone = tick && bitIdx === 10.U

  // ── FSM ────────────────────────────────────────────────────
  state := MuxLookup(state.asUInt, Idle)(Seq(
    Idle.asUInt  -> Mux(sending, Shift, Idle),
    Shift.asUInt -> Mux(byteDone, Idle, Shift)
  ))

  io.in.ready := state === Idle

  when(sending) {
    shift  := Cat(1.U(1.W), io.in.bits, 0.U(1.W))
    bitIdx := 0.U
  }

  when(state === Shift && tick) {
    when(!byteDone) {
      shift  := Cat(1.U(1.W), shift(9, 1))
      bitIdx := bitIdx + 1.U
    }
  }

  io.txd := shift(0)
}
