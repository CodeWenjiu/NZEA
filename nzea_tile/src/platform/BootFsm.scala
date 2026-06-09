package nzea_tile.platform

import chisel3._
import chisel3.util.{is, switch, Cat, Enum}

class BootFsm extends Module {

  val io = IO(new Bundle {
    val boot_en = Input(Bool())
    val rx_valid = Input(Bool())
    val rx_data = Input(UInt(8.W))
    val ram_wen = Output(Bool())
    val ram_addr = Output(UInt(15.W))
    val ram_wdata = Output(UInt(32.W))
    val cpu_reset = Output(Bool())
  })

  val sInit :: sIdle :: sAddr :: sSize :: sData :: sDone :: Nil = Enum(6)
  val state = RegInit(sInit)
  val shift = RegInit(0.U(32.W))
  val byteCnt = RegInit(0.U(2.W))
  val wordCnt = RegInit(0.U(32.W))
  val totalWords = RegInit(0.U(32.W))
  val wordAddr = RegInit(0.U(15.W))

  io.cpu_reset := io.boot_en && (state === sAddr || state === sSize || state === sData)

  io.ram_wen := false.B
  io.ram_addr := wordAddr
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
      when(magicLow === "hB007B007".U) {
        state := sAddr
        byteCnt := 0.U
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
        wordAddr := Cat(io.rx_data, shift(31, 8))(14, 0)
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
