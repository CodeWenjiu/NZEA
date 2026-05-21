package nzea_tile.platform.hellofpga

import chisel3._
import chisel3.util.{Cat, Enum, log2Ceil, switch, is}
import nzea_rtl.FabricBusRW

class UartIo extends Bundle {
  val txd       = Output(Bool())
  val rxd       = Input(Bool())
  val rtsn      = Output(Bool())
  val ctsn      = Input(Bool())
}

class FabricBusUart(simClkHz: Int = 100_000_000, baudRate: Int = 100000) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth = 32, dataWidth = 32, userWidth = 32, idWidth = 8))
    val txd = Output(Bool()); val rxd = Input(Bool()); val rtsn = Output(Bool())
    val ctsn = Input(Bool())
    val boot_rx_valid = Output(Bool())
    val boot_rx_data  = Output(UInt(8.W))
  })
  private val rxStart = WireDefault(false.B)
  private val txStart = WireDefault(false.B)
  private val divisor = simClkHz / baudRate
  private val divCntBits = log2Ceil(divisor.max(1))
  private val divCnt = RegInit(0.U(divCntBits.W))
  private val baudTick = divCnt === (divisor - 1).U(divCntBits.W)
  when(txStart) { divCnt := 0.U(divCntBits.W) }
  .elsewhen(rxStart) { divCnt := (divisor / 2).U(divCntBits.W) }
  .elsewhen(baudTick) { divCnt := 0.U(divCntBits.W) }
  .otherwise { divCnt := divCnt + 1.U }

  val dlab = RegInit(false.B); val thr = RegInit(0.U(8.W)); val rbr = RegInit(0.U(8.W))
  val ier = RegInit(0.U(8.W)); val iir = RegInit(1.U(4.W)); val lcr = RegInit(3.U(8.W))
  val lsr = RegInit(0x60.U(8.W)); val dll = RegInit(0.U(8.W)); val dlm = RegInit(0.U(8.W))
  val mcr = RegInit(0.U(8.W)); val msr = RegInit(0.U(8.W))

  val txSR = RegInit(1.U(10.W)); val txBitCnt = RegInit(0.U(4.W)); val txBusy = RegInit(false.B); val thrPending = RegInit(false.B)
  val rxSR = RegInit(1.U(9.W)); val rxBitCnt = RegInit(0.U(4.W))
  val rxActive = RegInit(false.B); val rxdD1 = RegInit(true.B); val rxdD2 = RegInit(true.B)

  io.txd := txSR(0); io.rtsn := false.B

  private val flush = io.bus.resp.flush; private val busy = RegInit(false.B)
  private val respUser = RegInit(0.U(32.W)); private val respId = RegInit(0.U(8.W)); private val respData = RegInit(0.U(32.W))

  private val reqFire = io.bus.req.valid && io.bus.req.ready
  private val wordLo  = io.bus.req.bits.addr(4, 2) === 0.U(3.W)
  private val wordHi  = io.bus.req.bits.addr(4, 2) === 1.U(3.W)
  private val wstrb   = io.bus.req.bits.wstrb
  private val thrWrite = io.bus.req.bits.wen && wordLo && !dlab && wstrb(0)

  io.bus.req.ready := !busy && !flush && !(thrWrite && txBusy && thrPending)
  io.bus.req.flush := false.B
  io.bus.resp.valid := busy && !flush; io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := respUser; io.bus.resp.bits.id := respId

  when(reqFire) {
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; busy := true.B
    val w = wstrb; val d = io.bus.req.bits.wdata
    when(io.bus.req.bits.wen) {
      when(wordLo) {
        when(w(0)) { when(dlab) { dll := d(7,0) } .otherwise { thr := d(7,0); when(txBusy) { thrPending := true.B } } }
        when(w(1)) { when(dlab) { dlm := d(15,8) } .otherwise { ier := d(15,8) } }
        when(w(3)) { lcr := d(31,24); dlab := d(31) }
      }.elsewhen(wordHi) {
        when(w(0)) { mcr := d(7,0) }
      }
    }.otherwise {
      when(wordLo) {
        respData := Cat(lcr, iir, Mux(dlab, dlm, ier), Mux(dlab, dll, rbr))
      }.elsewhen(wordHi) {
        respData := Cat(0.U(8.W), msr, lsr, mcr)
      }
    }
  }
  when(io.bus.resp.fire || flush) { busy := false.B }

  when(txBusy) {
    when(baudTick) {
      txBitCnt := txBitCnt + 1.U; txSR := Cat(1.U(1.W), txSR(9,1))
      when(txBitCnt === 9.U) {
        when(thrPending) {
          thrPending := false.B
          txSR := Cat(1.U(1.W), thr, 0.U(1.W)); txBitCnt := 0.U; txStart := true.B
        }.otherwise { txBusy := false.B }
      }
    }
  }.elsewhen(reqFire && thrWrite) {
    txSR := Cat(1.U(1.W), io.bus.req.bits.wdata(7,0), 0.U(1.W)); txBitCnt := 0.U; txBusy := true.B; txStart := true.B
  }

  rxdD1 := io.rxd; rxdD2 := rxdD1
  val rxDone  = WireDefault(false.B)
  val rxDummy = RegInit(false.B)
  when(rxActive) {
    when(baudTick) {
      when(rxDummy) { rxDummy := false.B }
      .otherwise {
        rxSR := Cat(rxdD2, rxSR(8,1)); rxBitCnt := rxBitCnt + 1.U
        when(rxBitCnt === 8.U) { rxActive := false.B; rbr := rxSR(8,1); rxDone := true.B }
      }
    }
  }.elsewhen(rxdD2 && !rxdD1) { rxActive := true.B; rxBitCnt := 0.U; rxStart := true.B; rxDummy := true.B }
  io.boot_rx_valid := RegNext(rxDone, false.B)
  io.boot_rx_data  := rbr
}
