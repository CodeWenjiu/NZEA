package nzea_device

import chisel3._
import chisel3.util.{Cat, Decoupled}

/** Minimal UART transmitter (8N1).
  *
  * Uses a down-counter for baud timing. On each new byte, loads the shift register with {stop,data,start} and counts
  * down. Combinational `txd` output driven by current bit position.
  */
class UartTx(clockHz: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val txd = Output(Bool())
    val in = Flipped(Decoupled(UInt(8.W)))
  })

  private val bitDiv = clockHz / baudRate

  val counter = RegInit(0.U(32.W))
  val busy = RegInit(false.B)
  val shiftReg = RegInit(0.U(10.W)) // {stop, data[7:0], start}
  val bitCnt = RegInit(0.U(4.W)) // 0=start, 1-8=data, 9=stop

  val sampleTick = counter === 0.U

  // ── Counter ────────────────────────────────────────────────
  when(!busy) {
    counter := bitDiv.U
  }.elsewhen(!sampleTick) {
    counter := counter - 1.U
  }.otherwise {
    counter := bitDiv.U
  }

  // ── Load new byte / shift ──────────────────────────────────
  io.in.ready := !busy

  when(busy && sampleTick) {
    when(bitCnt === 9.U) {
      busy := false.B
    }.otherwise {
      shiftReg := Cat(0.U(1.W), shiftReg(9, 1)) // shift right, fill with 0
    }
  }.elsewhen(!busy && io.in.valid) {
    busy := true.B
    shiftReg := Cat(1.U(1.W), io.in.bits, 0.U(1.W)) // {stop=1, data, start=0}
  }

  // ── Bit counter ────────────────────────────────────────────
  when(!busy) {
    bitCnt := 0.U
  }.elsewhen(sampleTick && busy) {
    bitCnt := bitCnt + 1.U
  }

  // ── Combinational txd (start=0, stop=1, data=shiftReg[0]) ──
  io.txd := Mux(
    !busy,
    true.B,
    Mux(
      bitCnt === 0.U,
      false.B, // start bit
      Mux(
        bitCnt === 9.U,
        true.B, // stop bit
        shiftReg(0)
      )
    )
  ) // data bit (LSB first)

}
