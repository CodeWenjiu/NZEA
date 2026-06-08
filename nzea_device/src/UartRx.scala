package nzea_device

import chisel3._
import chisel3.util.{Cat, Decoupled}

/** Minimal UART receiver (8N1).
  *
  * Based on the proven [[https://github.com/ultraembedded/core_soc/blob/master/src_v/uart_lite.v uart_lite]]
  * implementation. Uses a down-counter for baud timing with half-bit-period initial offset to sample at bit centers.
  */
class UartRx(clockHz: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val out = Decoupled(UInt(8.W))
  })

  // ── Baud counter ───────────────────────────────────────────
  private val bitDiv = clockHz / baudRate // full bit period in cycles
  private val bitDivHalf = bitDiv / 2 // half bit for center-of-start-bit

  val counter = RegInit(0.U(32.W))
  val sampleTick = counter === 0.U

  // ── Synchronize rxd ────────────────────────────────────────
  val rxdM = RegInit(true.B); rxdM := io.rxd
  val rxd = RegInit(true.B); rxd := rxdM

  // ── RX registers ───────────────────────────────────────────
  val busy = RegInit(false.B)
  val shiftReg = RegInit(0.U(8.W))
  val bitCnt = RegInit(0.U(4.W)) // 0=start, 1-8=data, 9=stop
  val rxReady = RegInit(false.B)
  val rxData = RegInit(0.U(8.W))

  // ── Baud counter logic ─────────────────────────────────────
  when(!busy) {
    counter := bitDivHalf.U // half-bit to center of start bit
  }.elsewhen(!sampleTick) {
    counter := counter - 1.U // count down
  }.otherwise { // sampleTick
    when((bitCnt === 9.U)) {
      counter := 0.U // done
    }.otherwise {
      counter := bitDiv.U // reload full bit period
    }
  }

  // ── Busy / data detection ──────────────────────────────────
  when(busy && sampleTick) {
    when(bitCnt === 9.U) {
      busy := false.B
    }.elsewhen(bitCnt === 0.U) {
      // center of start bit — should still be low
      when(rxd) {
        busy := false.B // false start
      }
    }.otherwise {
      // data bits 1-8
      shiftReg := Cat(rxd, shiftReg(7, 1))
    }
  }.elsewhen(!busy && !rxd) {
    // start bit detected (falling edge)
    busy := true.B
    shiftReg := 0.U
  }

  // ── Bit counter ────────────────────────────────────────────
  when(!busy) {
    bitCnt := 0.U
  }.elsewhen(sampleTick && busy) {
    bitCnt := bitCnt + 1.U
  }

  // ── Output logic (1-cycle pulse, like reference) ────────────
  when(rxReady) { rxReady := false.B } // auto-clear next cycle

  when(busy && sampleTick && bitCnt === 9.U) {
    when(rxd) {
      rxData := shiftReg
      rxReady := true.B
    }
  }

  io.out.valid := rxReady
  io.out.bits := rxData

}
