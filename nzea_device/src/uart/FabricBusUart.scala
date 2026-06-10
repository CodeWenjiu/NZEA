package nzea_device.uart

import chisel3._
import chisel3.util.Cat
import nzea_rtl.FabricBusRW

class UartIo extends Bundle {
  val txd = Output(Bool())
  val rxd = Input(Bool())
  val rtsn = Output(Bool())
  val ctsn = Input(Bool())
}

/** FabricBus-attached UART. Internally delegates TX/RX to [[nzea_device.UartTx]] / [[nzea_device.UartRx]]. */
class FabricBusUart(base: BigInt, simClkHz: Int = 100_000_000, baudRate: Int = 100000) extends Module {

  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth = 32, dataWidth = 32, userWidth = 32, idWidth = 8))
    val txd = Output(Bool()); val rxd = Input(Bool()); val rtsn = Output(Bool())
    val ctsn = Input(Bool())
    val boot_rx_valid = Output(Bool())
    val boot_rx_data = Output(UInt(8.W))
  })

  // ── UART core from nzea_device ─────────────────────────────
  val tx = Module(new UartTx(simClkHz, baudRate))
  val rx = Module(new UartRx(simClkHz, baudRate))
  tx.io.in.valid := false.B
  tx.io.in.bits := DontCare
  rx.io.rxd := io.rxd
  rx.io.out.ready := false.B

  io.txd := tx.io.txd; io.rtsn := false.B

  // ── Local address ───────────────────────────────────────────
  private val localAddr = io.bus.req.bits.addr - base.U(32.W)
  private val wordLo = localAddr(4, 2) === 0.U(3.W)
  private val wordHi = localAddr(4, 2) === 1.U(3.W)
  val dlab = RegInit(false.B); val thr = RegInit(0.U(8.W))
  val ier = RegInit(0.U(8.W)); val lcr = RegInit(3.U(8.W))
  val dll = RegInit(0.U(8.W)); val dlm = RegInit(0.U(8.W)); val mcr = RegInit(0.U(8.W))
  val txPending = RegInit(false.B)
  val rxValid = RegInit(false.B)
  val rxByte = RegInit(0.U(8.W))

  private def RbrW = rxByte
  private def LsrW = Cat(0.U(1.W), !txPending, !txPending, 0.U(1.W), rxValid, 0.U(3.W))

  // ── Bus interface ──────────────────────────────────────────
  private val flush = io.bus.resp.flush
  private val busy = RegInit(false.B)
  private val respUser = RegInit(0.U(32.W)); private val respId = RegInit(0.U(8.W))
  private val respData = RegInit(0.U(32.W))
  private val reqFire = io.bus.req.valid && io.bus.req.ready
  private val wstrb = io.bus.req.bits.wstrb
  private val thrWrite = io.bus.req.bits.wen && wordLo && !dlab && wstrb(0)

  io.bus.req.ready := !busy && !flush && !(thrWrite && txPending)
  io.bus.req.flush := false.B
  io.bus.resp.valid := busy && !flush; io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := respUser; io.bus.resp.bits.id := respId

  when(reqFire) {
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; busy := true.B
    val w = wstrb; val d = io.bus.req.bits.wdata
    when(io.bus.req.bits.wen) {
      when(wordLo) {
        when(w(0)) { when(dlab) { dll := d(7, 0) }.otherwise { thr := d(7, 0); txPending := true.B } }
        when(w(1)) { when(dlab) { dlm := d(15, 8) }.otherwise { ier := d(15, 8) } }
        when(w(3)) { lcr := d(31, 24); dlab := d(31) }
      }.elsewhen(wordHi) { when(w(0)) { mcr := d(7, 0) } }
    }.otherwise {
      when(wordLo) {
        respData := Cat(lcr, 0.U(4.W), RbrW, Mux(dlab, dlm, ier), Mux(dlab, dll, RbrW))
      }.elsewhen(wordHi) { respData := Cat(0.U(8.W), 0.U(8.W), LsrW, mcr) }
    }
  }

  when(io.bus.resp.fire || flush) { busy := false.B }

  // TX: bus writes THR → UartTx
  when(reqFire && thrWrite) { txPending := true.B }
  tx.io.in.valid := txPending && tx.io.in.ready
  tx.io.in.bits := thr
  when(tx.io.in.valid && tx.io.in.ready) { txPending := false.B }

  // RX → boot monitor
  io.boot_rx_valid := rx.io.out.valid
  io.boot_rx_data := rx.io.out.bits

  // RX data latch (on valid byte, hold until bus read)
  rx.io.out.ready := !rxValid
  when(rx.io.out.valid && !rxValid) { rxValid := true.B; rxByte := rx.io.out.bits }
  when(reqFire && !io.bus.req.bits.wen && wordLo) { rxValid := false.B } // read clears
}
