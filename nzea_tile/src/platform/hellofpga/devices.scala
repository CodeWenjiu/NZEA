package nzea_tile.platform.hellofpga

import chisel3._
import chisel3.util.{Cat, Enum, log2Ceil, switch, is}
import nzea_rtl.{FabricAddrRange, FabricBusRW}

object AddressMap {
  val ram                = FabricAddrRange(base = BigInt("80000000", 16), size = BigInt("00020000", 16))
  val uart               = FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  val sifiveTestFinisher = FabricAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val ranges: Seq[FabricAddrRange] = Seq(ram, uart, sifiveTestFinisher)
}

class UartIo extends Bundle {
  val txd       = Output(Bool())
  val rxd       = Input(Bool())
  val rtsn      = Output(Bool())
  val ctsn      = Input(Bool())
  val interrupt = Output(Bool())
}

class RamFabricSlave(
  addrWidth: Int,
  dataWidth: Int,
  userWidth: Int,
  idWidth: Int,
  baseAddr: BigInt
) extends Module {
  require(dataWidth == 32, s"RamFabricSlave expects 32-bit data, got $dataWidth")
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val boot_wen   = Input(Bool())
    val boot_addr  = Input(UInt(15.W))
    val boot_wdata = Input(UInt(32.W))
  })

  private val depth        = 1 << 15
  private val flush        = io.bus.resp.flush
  private val writeMaskAll = ((BigInt(1) << (dataWidth / 8)) - 1).U((dataWidth / 8).W)
  val mem = SyncReadMem(depth, UInt(dataWidth.W), SyncReadMem.WriteFirst)

  val busCycle = RegInit(0.U(2.W))
  val isRead   = RegInit(false.B)
  val respUser = RegInit(0.U(userWidth.W))
  val respId   = RegInit(0.U(idWidth.W))
  val readData = RegInit(0.U(dataWidth.W))

  val reqReady = busCycle === 0.U && !flush
  val reqFire  = io.bus.req.valid && reqReady
  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B
  io.bus.resp.valid      := busCycle === 3.U && !flush
  io.bus.resp.bits.data  := Mux(isRead, readData, 0.U)
  io.bus.resp.bits.user  := respUser
  io.bus.resp.bits.id    := respId

  val localByteAddr = io.bus.req.bits.addr - baseAddr.U(addrWidth.W)
  val wordAddr      = localByteAddr(16, 2)
  val wenClean = reqFire && io.bus.req.bits.wen
  val renClean = reqFire && !io.bus.req.bits.wen
  when(io.boot_wen) {
    mem.write(io.boot_addr, io.boot_wdata)
  }.elsewhen(wenClean) {
    mem.write(wordAddr, io.bus.req.bits.wdata)
  }
  val memRead = mem.read(wordAddr, renClean)
  readData := RegNext(memRead, 0.U(dataWidth.W))

  when(reqFire) {
    assert(io.bus.req.bits.addr(1, 0) === 0.U, "RamFabricSlave: unaligned access")
    when(io.bus.req.bits.wen) { assert(io.bus.req.bits.wstrb === writeMaskAll, "RamFabricSlave: partial write unsupported") }
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; isRead := !io.bus.req.bits.wen; busCycle := 1.U
  }
  when(busCycle === 1.U) { busCycle := 2.U }
  when(busCycle === 2.U) { busCycle := 3.U }
  when((busCycle === 3.U && io.bus.resp.ready) || flush) { busCycle := 0.U }
}

class FabricBusUart(simClkHz: Int = 100_000_000, baudRate: Int = 1000000) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth = 32, dataWidth = 32, userWidth = 32, idWidth = 8))
    val txd = Output(Bool()); val rxd = Input(Bool()); val rtsn = Output(Bool())
    val ctsn = Input(Bool()); val interrupt = Output(Bool())
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

  val txSR = RegInit(1.U(10.W)); val txBitCnt = RegInit(0.U(4.W)); val txBusy = RegInit(false.B)
  val rxSR = RegInit(1.U(9.W)); val rxBitCnt = RegInit(0.U(4.W))
  val rxActive = RegInit(false.B); val rxdD1 = RegInit(true.B); val rxdD2 = RegInit(true.B)

  io.txd := txSR(0); io.rtsn := false.B; io.interrupt := RegNext(lsr(0) && ier(0), false.B)

  private val flush = io.bus.resp.flush; private val busy = RegInit(false.B)
  private val respUser = RegInit(0.U(32.W)); private val respId = RegInit(0.U(8.W)); private val respData = RegInit(0.U(32.W))

  io.bus.req.ready := !busy && !flush; io.bus.req.flush := false.B
  io.bus.resp.valid := busy && !flush; io.bus.resp.bits.data := respData
  io.bus.resp.bits.user := respUser; io.bus.resp.bits.id := respId

  private val reqFire = io.bus.req.valid && io.bus.req.ready
  private val byteSel = io.bus.req.bits.addr(2, 0)
  when(reqFire) {
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; busy := true.B
    when(io.bus.req.bits.wen) {
      switch(byteSel) {
        is(0x0.U) { when(dlab) { dll := io.bus.req.bits.wdata(7,0); dlm := io.bus.req.bits.wdata(15,8) } .otherwise { thr := io.bus.req.bits.wdata(7,0) } }
        is(0x4.U) { ier := io.bus.req.bits.wdata(7,0) }
        is(0x8.U) {}
        is(0xc.U) { lcr := io.bus.req.bits.wdata(7,0); dlab := io.bus.req.bits.wdata(7) }
        is(0x10.U) { mcr := io.bus.req.bits.wdata(7,0) }
      }
    }.otherwise {
      switch(byteSel) {
        is(0x0.U) { respData := Mux(dlab, Cat(dlm,dll), rbr) }
        is(0x4.U) { respData := Mux(dlab, 0.U, ier) }
        is(0x8.U) { respData := iir }
        is(0xc.U) { respData := lcr }
        is(0x10.U) { respData := mcr }
        is(0x14.U) { respData := lsr }
        is(0x18.U) { respData := msr }
      }
    }
  }
  when(io.bus.resp.fire || flush) { busy := false.B }

  when(txBusy) {
    when(baudTick) { txBitCnt := txBitCnt + 1.U; txSR := Cat(1.U(1.W), txSR(9,1)); when(txBitCnt === 9.U) { txBusy := false.B; lsr := lsr(7,1).asUInt ## true.B } }
  }.elsewhen(reqFire && io.bus.req.bits.wen && byteSel === 0x0.U && !dlab) {
    txSR := Cat(1.U(1.W), io.bus.req.bits.wdata(7,0), 0.U(1.W)); txBitCnt := 0.U; txBusy := true.B; lsr := lsr(7,1).asUInt ## false.B; txStart := true.B
  }

  rxdD1 := io.rxd; rxdD2 := rxdD1
  val rxDone  = WireDefault(false.B)
  val rxDummy = RegInit(false.B)  // skip first baudTick after start (falls in start bit)
  when(rxActive) {
    when(baudTick) {
      when(rxDummy) { rxDummy := false.B }
      .otherwise {
        rxSR := Cat(rxdD2, rxSR(8,1)); rxBitCnt := rxBitCnt + 1.U
        when(rxBitCnt === 8.U) { rxActive := false.B; rbr := rxSR(8,1); lsr := 1.U ## lsr(6,1); rxDone := true.B }
      }
    }
  }.elsewhen(rxdD2 && !rxdD1) { rxActive := true.B; rxBitCnt := 0.U; rxStart := true.B; rxDummy := true.B }
  io.boot_rx_valid := RegNext(rxDone, false.B)
  io.boot_rx_data  := rbr
  when(reqFire && !io.bus.req.bits.wen && byteSel === 0x14.U) { lsr := 0.U ## lsr(6,1) }
}

class BootFsm extends Module {
  val io = IO(new Bundle {
    val boot_en   = Input(Bool())
    val rx_valid = Input(Bool())
    val rx_data  = Input(UInt(8.W))
    val ram_wen   = Output(Bool())
    val ram_addr  = Output(UInt(15.W))
    val ram_wdata = Output(UInt(32.W))
    val cpu_reset = Output(Bool())
  })

  val sIdle :: sMagic :: sAddr :: sSize :: sData :: sDone :: Nil = Enum(6)
  val state      = RegInit(sIdle)
  val shift      = RegInit(0.U(32.W))
  val byteCnt    = RegInit(0.U(2.W))
  val wordCnt    = RegInit(0.U(32.W))
  val totalWords = RegInit(0.U(32.W))
  val wordAddr   = RegInit(0.U(15.W))

  io.cpu_reset := io.boot_en && state =/= sDone

  io.ram_wen   := false.B
  io.ram_addr  := wordAddr
  io.ram_wdata := Cat(io.rx_data, shift(31, 8))  // include current byte in word

  when(io.rx_valid) {
    shift := Cat(io.rx_data, shift(31, 8))
    byteCnt := byteCnt + 1.U
  }

  val magicLow  = RegInit(0.U(32.W))
  when(io.rx_valid) {
    magicLow := Cat(io.rx_data, magicLow(31, 8))
  }

  switch(state) {
    is(sIdle) {
      when(magicLow === "hB007B007".U) {
        state := sAddr
        byteCnt := 0.U
      }
    }
    is(sAddr) {
      when(byteCnt === 3.U && io.rx_valid) {
        wordAddr := Cat(io.rx_data, shift(31, 8))(14, 0)
        byteCnt  := 0.U
        state    := sSize
      }
    }
    is(sSize) {
      when(byteCnt === 3.U && io.rx_valid) {
        totalWords := Cat(io.rx_data, shift(31, 8))
        wordCnt    := 0.U
        byteCnt    := 0.U
        state      := sData
      }
    }
    is(sData) {
      when(byteCnt === 3.U && io.rx_valid) {
        io.ram_wen := true.B
        wordCnt    := wordCnt + 1.U
        wordAddr   := wordAddr + 1.U
        byteCnt    := 0.U
        when(wordCnt + 1.U === totalWords) {
          state := sDone
        }
      }
    }
    is(sDone) {}
  }
}

/** SiFive-compatible test finisher: write any value to trigger finish.
  * Standard (0x5555 = pass) can be checked at TB level.
  */
class SifiveTestFinisher(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
    val finished = Output(Bool())
  })

  private val flush       = io.bus.resp.flush
  private val finished    = RegInit(false.B)
  private val busCycle    = RegInit(0.U(2.W))
  private val respUser    = RegInit(0.U(userWidth.W))
  private val respId      = RegInit(0.U(idWidth.W))

  private val reqReady = busCycle === 0.U && !flush
  private val reqFire  = io.bus.req.valid && reqReady
  io.bus.req.ready := reqReady
  io.bus.req.flush := false.B
  io.bus.resp.valid      := busCycle === 3.U && !flush
  io.bus.resp.bits.data  := 0.U
  io.bus.resp.bits.user  := respUser
  io.bus.resp.bits.id    := respId

  when(reqFire) {
    respUser := io.bus.req.bits.user; respId := io.bus.req.bits.id; busCycle := 1.U
    when(io.bus.req.bits.wen) { finished := true.B }
  }
  when(busCycle === 1.U) { busCycle := 2.U }
  when(busCycle === 2.U) { busCycle := 3.U }
  when((busCycle === 3.U && io.bus.resp.ready) || flush) { busCycle := 0.U }

  io.finished := finished
}
