package nzea_device

import chisel3._
import chisel3.util.{Cat, Decoupled, Enum, is, switch}
import nzea_rtl.DdsNco

/** Minimal UART receiver (8N1).
  *
  * Samples at mid-bit using [[DdsNco]] for baud timing. After detecting a start bit (1→0), waits half a bit period,
  * then samples 8 data bits + stop bit. Outputs received bytes via `io.out` (Decoupled).
  *
  * @param clockHz  system clock in Hz
  * @param baudRate desired baud rate
  */
class UartRx(clockHz: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val out = Decoupled(UInt(8.W))
  })

  // ── Start-bit edge detection ───────────────────────────────
  val rxdPrev = RegInit(true.B); rxdPrev := io.rxd  // match idle line
  val startBit = rxdPrev && !io.rxd  // falling edge: idle(1) → start(0)

  // ── DDS baud tick (reset on start bit for mid-bit alignment) ─
  val dds = Module(new DdsNco(clockHz, baudRate))
  dds.io.resetPhase := startBit
  val baudTick = dds.io.tick

  // ── RX FSM ─────────────────────────────────────────────────
  val sIdle :: sStart :: sData :: sStop :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val halfTick = RegInit(false.B) // true after first tick in sStart

  val shiftReg  = RegInit(0.U(8.W))
  val bitCnt    = RegInit(0.U(3.W)) // 0..7 data bits

  io.out.valid := false.B
  io.out.bits  := DontCare

  switch(state) {
    is(sIdle) {
      when(startBit) {
        state    := sStart
        halfTick := false.B
      }
    }
    is(sStart) {
      when(baudTick) {
        when(halfTick) {
          // waited 1.5 bit periods; reset DDS → sample data at bit centers
          dds.io.resetPhase := true.B
          state    := sData
          bitCnt   := 0.U
          shiftReg := 0.U
        }.otherwise {
          halfTick := true.B
        }
      }
    }
    is(sData) {
      when(baudTick) {
        shiftReg := Cat(io.rxd, shiftReg(7, 1))
        when(bitCnt === 7.U) {
          state := sStop
        }.otherwise {
          bitCnt := bitCnt + 1.U
        }
      }
    }
    is(sStop) {
      when(baudTick) {
        // if stop bit is 0 → framing error; discard
        when(io.rxd) {
          io.out.valid := true.B
          io.out.bits  := shiftReg
        }
        state := sDone
      }
    }
    is(sDone) {
      io.out.valid := true.B
      io.out.bits  := shiftReg
      when(io.out.ready) {
        state := sIdle
      }
    }
  }
}
