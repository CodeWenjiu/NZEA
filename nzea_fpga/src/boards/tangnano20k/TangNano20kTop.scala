package nzea_fpga.boards.tangnano20k

import chisel3._
import chisel3.util.{log2Ceil, Cat, Decoupled, MuxLookup}
import nzea_device.UartTx

/** Tang Nano 20K LED demo + UART TX ("hello_world\\n" every 1s).
  *
  * Ports match the tangnano20k.cst constraint file exactly. Equivalent to the original hand-written top.v: no NzeaTile,
  * pure standalone demo.
  */
class TangNano20kTop(clockHz: Int)(implicit config: nzea_core.config.CoreConfig) extends RawModule {
  private val clkFreq = 100_000_000
  val clk = IO(Input(Clock()))
  val s1 = IO(Input(Bool()))
  val s2 = IO(Input(Bool()))
  val uart_rx = IO(Input(Bool()))
  val uart_tx = IO(Output(Bool()))
  val led = IO(Output(UInt(6.W)))

  // POR: active for 1 cycle so firtool preserves register initial values
  val noReset = Wire(Bool()); noReset := false.B
  val por = withClockAndReset(clk, noReset) { RegInit(true.B) }
  por := false.B

  // s1 reset synchronizer (s1 active-low, pulled up: press → run, release → reset)
  val resetS1 = withClockAndReset(clk, noReset) { RegInit(true.B) }
  resetS1 := s1
  val resetS2 = withClockAndReset(clk, noReset) { RegInit(true.B) }
  resetS2 := resetS1
  val rst = por || resetS2 // POR or s1-released

  // ── LED pattern shifter (every 0.5s) ───────────────────────
  val halfSec = (clkFreq / 2).U
  val ledCnt = withClockAndReset(clk, rst) { RegInit(0.U(26.W)) }
  val phase = withClockAndReset(clk, rst) { RegInit(0.U(2.W)) }

  when(ledCnt === halfSec) {
    ledCnt := 0.U
    phase := phase + 1.U
  }.otherwise {
    ledCnt := ledCnt + 1.U
  }

  // 4-bit one-hot from 2-bit phase: 1110, 1101, 1011, 0111
  val patterns = VecInit(
    Seq(
      "b1110".U(4.W),
      "b1101".U(4.W),
      "b1011".U(4.W),
      "b0111".U(4.W)
    )
  )

  val pattern = patterns(phase)

  // ── UART TX (115200 baud 8N1) ──────────────────────────────
  val baudRate = 115200

  val uart = withClockAndReset(clk, rst) {
    Module(new UartTx(clkFreq, baudRate))
  }

  uart_tx := uart.io.txd

  // ── Message feeder (sends "hello_world\r\n" every ~1s) ────
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

  val timer = withClockAndReset(clk, rst) { RegInit(0.U(log2Ceil(clkFreq + 1).W)) }
  val fire = timer === (clkFreq - 1).U // 1 second

  when(fire) {
    timer := 0.U
  }.otherwise {
    timer := timer + 1.U
  }

  // Character index & feeding
  val charIdx = withClockAndReset(clk, rst) { RegInit(0.U(log2Ceil(msgCount + 1).W)) }

  object FeedState extends ChiselEnum {
    val WaitFire, SendChar = Value
  }

  import FeedState._

  val feedState = withClockAndReset(clk, rst) { RegInit(WaitFire) }

  uart.io.in.valid := false.B
  uart.io.in.bits := DontCare

  feedState := MuxLookup(feedState.asUInt, WaitFire)(
    Seq(
      WaitFire.asUInt -> Mux(fire, SendChar, WaitFire),
      SendChar.asUInt -> Mux(uart.io.in.ready, Mux(charIdx === (msgCount - 1).U, WaitFire, SendChar), SendChar)
    )
  )

  when(feedState === WaitFire && fire) {
    charIdx := 0.U
  }

  when(feedState === SendChar) {
    uart.io.in.valid := true.B
    uart.io.in.bits := msg(charIdx)
    when(uart.io.in.ready) {
      when(charIdx === (msgCount - 1).U) {
        charIdx := 0.U
      }.otherwise {
        charIdx := charIdx + 1.U
      }
    }
  }

  // ── Outputs ─────────────────────────────────────────────────
  led := Cat(~s2, ~s1, pattern)
}
