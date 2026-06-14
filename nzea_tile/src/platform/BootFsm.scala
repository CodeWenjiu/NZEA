package nzea_tile.platform

import chisel3._
import chisel3.util.{is, switch, Cat, Enum, log2Ceil}
import nzea_rtl.Mrom

class BootFsm(
    ramDepth: Int = 32768,
    defaultHex: String = "nzea_sim/sim/tile/hello.hex"
) extends Module {

  private val addrW = log2Ceil(ramDepth)

  val io = IO(new Bundle {
    val boot_en = Input(Bool())
    val rx_valid = Input(Bool())
    val rx_data = Input(UInt(8.W))
    val ram_wen = Output(Bool())
    val ram_addr = Output(UInt(addrW.W))
    val ram_wdata = Output(UInt(32.W))
    val cpu_reset = Output(Bool())
  })

  val sInit :: sPad :: sIdle :: sAddr :: sSize :: sData :: sDone :: Nil = Enum(7)
  val state = RegInit(sInit)
  val shift = RegInit(0.U(32.W))
  val byteCnt = RegInit(0.U(2.W))
  val wordCnt = RegInit(0.U(32.W))
  val totalWords = RegInit(0.U(32.W))
  val wordAddr = RegInit(0.U(addrW.W))

  // Default program ROM — depth and dataWidth auto-detected from hex
  val mrom = Module(new Mrom(defaultHex))
  val initCnt = RegInit(0.U(log2Ceil(mrom.depth).W))
  // Safety-fill counter: covers the rest of RAM after MROM region
  val padCnt = RegInit(0.U(addrW.W))

  mrom.io.addr := initCnt

  io.cpu_reset := io.boot_en && (state =/= sIdle && state =/= sDone)

  io.ram_wen := false.B
  io.ram_addr := Mux(state === sInit, initCnt, Mux(state === sPad, padCnt, wordAddr))
  io.ram_wdata := Cat(io.rx_data, shift(31, 8))

  when(io.rx_valid) {
    shift := Cat(io.rx_data, shift(31, 8))
    byteCnt := byteCnt + 1.U
  }

  val magicLow = RegInit(0.U(32.W))

  when(io.rx_valid) {
    magicLow := Cat(io.rx_data, magicLow(31, 8))
  }

  switch(state) {
    is(sInit) {
      // Phase 1: fill MROM contents (depth from hex file).
      io.ram_wen := true.B
      io.ram_wdata := mrom.io.data
      when(initCnt === (mrom.depth - 1).U) {
        state := sPad
        padCnt := mrom.depth.U
      }.otherwise {
        initCnt := initCnt + 1.U
      }
    }
    is(sPad) {
      // Phase 2: fill remaining RAM with jal x0,0 (safe infinite loop).
      io.ram_wen := true.B
      io.ram_wdata := "h0000006F".U(32.W)
      when(padCnt === (ramDepth - 1).U) {
        state := sIdle
        wordAddr := 0.U
      }.otherwise {
        padCnt := padCnt + 1.U
      }
    }
    is(sIdle) {
      when(magicLow === "hB007B007".U) {
        state := sAddr
        byteCnt := 0.U
      }
    }
    is(sAddr) {
      when(byteCnt === 3.U && io.rx_valid) {
        wordAddr := Cat(io.rx_data, shift(31, 8))
        byteCnt := 0.U
        state := sSize
      }
    }
    is(sSize) {
      when(byteCnt === 3.U && io.rx_valid) {
        totalWords := Cat(io.rx_data, shift(31, 8))
        wordCnt := 0.U
        byteCnt := 0.U
        state := sData
      }
    }
    is(sData) {
      when(byteCnt === 3.U && io.rx_valid) {
        io.ram_wen := true.B
        wordCnt := wordCnt + 1.U
        wordAddr := wordAddr + 1.U
        byteCnt := 0.U
        when(wordCnt + 1.U === totalWords) {
          state := sDone
        }
      }
    }
    is(sDone) { state := sIdle }
  }

}
