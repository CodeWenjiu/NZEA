package nzea_fpga.boards.tangnano20k

import chisel3._
import chisel3.util.{log2Ceil, Cat, MuxLookup}
import nzea_device.{UartRx, UartTx}

/** Tang Nano 20K core logic. Uses reference UART RX (Verilog BlackBox) to isolate RX issues from data path. */
class TangNano20kCore(clkFreq: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val switch = Input(Bool())
    val led = Output(UInt(6.W))
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
  })

  // ── LED pattern ────────────────────────────────────────────
  val halfSec = (clkFreq / 2).U
  val ledCnt = RegInit(0.U(26.W))
  val phase = RegInit(0.U(3.W))

  when(ledCnt === halfSec) {
    ledCnt := 0.U
    phase := Mux(phase === 4.U, 0.U, phase + 1.U)
  }.otherwise { ledCnt := ledCnt + 1.U }

  val patterns = VecInit(Seq("b00001".U(5.W), "b00010".U(5.W), "b00100".U(5.W), "b01000".U(5.W), "b10000".U(5.W)))

  val rxStretch = RegInit(0.U(27.W))
  val rxActive = rxStretch > 0.U
  when(rxStretch > 0.U) { rxStretch := rxStretch - 1.U }

  // ── UART (TX = Chisel, RX = ref Verilog BlackBox) ──────────
  val uartTx = Module(new UartTx(clkFreq, baudRate))
  io.uart_tx := uartTx.io.txd

  val uartRx = Module(new UartRx(clkFreq, baudRate))
  uartRx.io.rxd := io.uart_rx
  uartRx.io.out.ready := true.B // always ready; byte pulses for 1 cycle

  when(uartRx.io.out.valid) { rxStretch := (clkFreq - 1).U }

  // ── RX line buffer ─────────────────────────────────────────
  val bufLen = 16
  val buf = RegInit(VecInit(Seq.fill(bufLen)(0.U(8.W))))
  val bufWr = RegInit(0.U(log2Ceil(bufLen).W))
  val echoRdy = RegInit(false.B)

  val rxReady = bufWr < (bufLen - 1).U

  when(uartRx.io.out.valid && rxReady) {
    buf(bufWr) := uartRx.io.out.bits
    bufWr := bufWr + 1.U
    when(bufWr + 1.U === (bufLen - 1).U) { echoRdy := true.B }
  }

  // ── Message ROM ────────────────────────────────────────────
  val msg = VecInit(
    Seq[UInt](
      0x68.U(8.W),
      0x65.U(8.W),
      0x6c.U(8.W),
      0x6c.U(8.W),
      0x6f.U(8.W),
      0x5f.U(8.W),
      0x77.U(8.W),
      0x6f.U(8.W),
      0x72.U(8.W),
      0x6c.U(8.W),
      0x64.U(8.W),
      0x0d.U(8.W),
      0x0a.U(8.W)
    )
  )

  val msgCount = msg.length

  // ── 1-second timer ────────────────────────────────────────
  val timer = RegInit(0.U(log2Ceil(clkFreq + 1).W))
  val fire = timer === (clkFreq - 1).U
  when(fire) { timer := 0.U }.otherwise { timer := timer + 1.U }

  // ── TX feeder FSM ──────────────────────────────────────────
  object TxPhase extends ChiselEnum { val WaitFire, Echo, Msg = Value }
  import TxPhase._

  val txPhase = RegInit(WaitFire)
  val txIdx = RegInit(0.U(log2Ceil(bufLen.max(msgCount)).W))
  val echoLen = RegInit(0.U(log2Ceil(bufLen).W))

  uartTx.io.in.valid := false.B
  uartTx.io.in.bits := DontCare

  txPhase := MuxLookup(txPhase.asUInt, WaitFire)(
    Seq(
      WaitFire.asUInt -> Mux(fire, Mux(echoRdy, Echo, Msg), WaitFire),
      Echo.asUInt -> Mux(uartTx.io.in.ready && txIdx === echoLen - 1.U, Msg, Echo),
      Msg.asUInt -> Mux(uartTx.io.in.ready && txIdx === (msgCount - 1).U, WaitFire, Msg)
    )
  )

  when(fire) { txIdx := 0.U; echoLen := Mux(echoRdy, bufWr, 0.U) }

  when(txPhase === Echo) {
    uartTx.io.in.valid := true.B
    uartTx.io.in.bits := buf(txIdx)
    when(uartTx.io.in.ready) { txIdx := txIdx + 1.U }
  }

  when(txPhase === Msg) {
    uartTx.io.in.valid := true.B
    uartTx.io.in.bits := msg(txIdx)
    when(uartTx.io.in.ready) { txIdx := txIdx + 1.U }
  }

  when(txPhase === Msg && uartTx.io.in.ready && txIdx === (msgCount - 1).U) { txIdx := 0.U }

  when(txPhase === Echo && uartTx.io.in.ready && txIdx === echoLen - 1.U) {
    echoRdy := false.B
    bufWr := 0.U
    txIdx := 0.U
  }

  // ── LED output ─────────────────────────────────────────────
  io.led := Cat(rxActive.asUInt, patterns(phase))
}
