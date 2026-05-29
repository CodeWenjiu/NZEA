package nzea_fpga.boards.tangnano20k

import chisel3._
import chisel3.util.{Cat, log2Ceil, MuxLookup}
import nzea_device.Uart

/** Tang Nano 20K core logic (active-high LEDs, clean reset).
  *
  * No polarity concerns — the outer RawModule handles pin inversion.
  */
class TangNano20kCore(clkFreq: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val switch  = Input(Bool())
    val led     = Output(UInt(6.W))  // [5]=rxActive, [4:0]=pattern
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
  })

  // ── LED pattern (5-bit walking one) ───────────────────────
  val halfSec  = (clkFreq / 2).U
  val ledCnt   = RegInit(0.U(26.W))
  val phase    = RegInit(0.U(3.W))  // 0..4

  when(ledCnt === halfSec) {
    ledCnt := 0.U
    phase  := Mux(phase === 4.U, 0.U, phase + 1.U)
  }.otherwise {
    ledCnt := ledCnt + 1.U
  }

  val patterns = VecInit(Seq(
    "b00001".U(5.W), "b00010".U(5.W), "b00100".U(5.W), "b01000".U(5.W), "b10000".U(5.W)
  ))
  val pattern  = patterns(phase)

  val rxStretch  = RegInit(0.U(27.W))
  val rxActive   = rxStretch > 0.U
  when(rxStretch > 0.U) { rxStretch := rxStretch - 1.U }

  // ── UART ───────────────────────────────────────────────────
  val uart = Module(new Uart(clkFreq, baudRate))
  io.uart_tx      := uart.io.txd
  uart.io.rxd     := io.uart_rx

  // Debug: trigger on valid alone (ignore ready)
  when(uart.io.rx.valid) { rxStretch := (clkFreq - 1).U }

  // ── RX line buffer (16 bytes) ──────────────────────────────
  val bufLen  = 16
  val buf     = RegInit(VecInit(Seq.fill(bufLen)(0.U(8.W))))
  val bufWr   = RegInit(0.U(log2Ceil(bufLen).W))
  val echoRdy = RegInit(false.B)

  uart.io.rx.ready := bufWr < (bufLen - 1).U

  when(uart.io.rx.valid && uart.io.rx.ready) {
    val ch = uart.io.rx.bits
    buf(bufWr) := ch
    bufWr      := bufWr + 1.U
    when(ch === 0x0d.U || ch === 0x0a.U || bufWr + 1.U === (bufLen - 1).U) {
      echoRdy := true.B
    }
  }

  // ── Message ROM ────────────────────────────────────────────
  val msg = VecInit(Seq[UInt](
    0x68.U(8.W), 0x65.U(8.W), 0x6c.U(8.W), 0x6c.U(8.W),
    0x6f.U(8.W), 0x5f.U(8.W), 0x77.U(8.W), 0x6f.U(8.W),
    0x72.U(8.W), 0x6c.U(8.W), 0x64.U(8.W), 0x0d.U(8.W), 0x0a.U(8.W)
  ))
  val msgCount = msg.length

  // ── 1-second timer ────────────────────────────────────────
  val timer = RegInit(0.U(log2Ceil(clkFreq + 1).W))
  val fire  = timer === (clkFreq - 1).U
  when(fire) { timer := 0.U }.otherwise { timer := timer + 1.U }

  // ── TX feeder FSM: Echo → Msg → Wait ──────────────────────
  object TxPhase extends ChiselEnum { val WaitFire, Echo, Msg = Value }
  import TxPhase._

  val txPhase = RegInit(WaitFire)
  val txIdx   = RegInit(0.U(log2Ceil(bufLen.max(msgCount)).W))
  val echoLen = RegInit(0.U(log2Ceil(bufLen).W))

  uart.io.tx.valid := false.B
  uart.io.tx.bits  := DontCare

  txPhase := MuxLookup(txPhase.asUInt, WaitFire)(Seq(
    WaitFire.asUInt -> Mux(fire, Mux(echoRdy, Echo, Msg), WaitFire),
    Echo.asUInt     -> Mux(uart.io.tx.ready && txIdx === echoLen - 1.U, Msg, Echo),
    Msg.asUInt      -> Mux(uart.io.tx.ready && txIdx === (msgCount - 1).U, WaitFire, Msg)
  ))

  when(fire) { txIdx := 0.U; echoLen := Mux(echoRdy, bufWr, 0.U) }

  when(txPhase === Echo) {
    uart.io.tx.valid := true.B
    uart.io.tx.bits  := buf(txIdx)
    when(uart.io.tx.ready) { txIdx := txIdx + 1.U }
  }

  when(txPhase === Msg) {
    uart.io.tx.valid := true.B
    uart.io.tx.bits  := msg(txIdx)
    when(uart.io.tx.ready) { txIdx := txIdx + 1.U }
  }

  when(txPhase === Msg && uart.io.tx.ready && txIdx === (msgCount - 1).U) {
    txIdx := 0.U
  }

  when(txPhase === Echo && uart.io.tx.ready && txIdx === echoLen - 1.U) {
    echoRdy := false.B
    bufWr   := 0.U
    txIdx   := 0.U  // reset for Msg phase
  }

  // ── LED output (active-high) ───────────────────────────────
  io.led := Cat(rxActive.asUInt, pattern)
}
