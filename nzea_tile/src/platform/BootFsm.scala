package nzea_tile.platform

import chisel3._
import chisel3.util.{is, log2Ceil, switch, Cat, Enum, Valid}
import nzea_rtl.{BootReq, Mrom}

/** UART boot monitor for the tile: loads a program into RAM over the boot protocol.
  *
  * Wire format (all little-endian): `magic(0xB007B007) + addr + size + size * word`. On power-on the mrom is copied to
  * RAM (index 0 = reset vector) and the rest is padded with `j .`; afterwards the FSM scans the RX stream for the magic
  * in `sIdle`. A load with `size = 0` is the *reset-only* command: no RAM is written, the CPU is held in reset through
  * the header and released via `sDone`, so it restarts the program already resident in RAM.
  *
  * `magicLow` is cleared in `sDone` so a program whose final 4 bytes equal the magic does not re-trigger the boot
  * sequence right after a load.
  */
class BootFsm(
    ramDepth: Int,
    hexPath: String
) extends Module {

  private val addrW = log2Ceil(ramDepth)

  val io = IO(new Bundle {
    val boot_en = Input(Bool())
    val rx_valid = Input(Bool())
    val rx_data = Input(UInt(8.W))
    val boot = Valid(new BootReq(addrW))
    val cpu_reset = Output(Bool())
  })

  val sInit :: sPad :: sIdle :: sAddr :: sSize :: sData :: sDone :: Nil = Enum(7)
  val state = RegInit(sInit)
  val shift = RegInit(0.U(32.W))
  val byteCnt = RegInit(0.U(2.W))
  val wordCnt = RegInit(0.U(32.W))
  val totalWords = RegInit(0.U(32.W))
  val wordAddr = RegInit(0.U(addrW.W))

  val mrom = Module(new Mrom(hexPath))
  val initCnt = RegInit(0.U(log2Ceil(mrom.depth).W))
  val padCnt = RegInit(0.U(addrW.W))

  mrom.io.addr := initCnt

  io.cpu_reset := io.boot_en && (state =/= sIdle && state =/= sDone)

  io.boot.valid := false.B
  io.boot.bits.addr := Mux(state === sInit, initCnt, Mux(state === sPad, padCnt, wordAddr))
  io.boot.bits.wdata := Cat(io.rx_data, shift(31, 8))

  when(io.rx_valid) {
    shift := Cat(io.rx_data, shift(31, 8))
    byteCnt := byteCnt + 1.U
  }

  val magicLow = RegInit(0.U(32.W))
  when(io.rx_valid) { magicLow := Cat(io.rx_data, magicLow(31, 8)) }

  val nextSize = Cat(io.rx_data, shift(31, 8))

  switch(state) {
    is(sInit) {
      io.boot.valid := true.B
      io.boot.bits.wdata := mrom.io.data
      when(initCnt === (mrom.depth - 1).U) {
        state := sPad
        padCnt := mrom.depth.U
      }.otherwise { initCnt := initCnt + 1.U }
    }
    is(sPad) {
      io.boot.valid := true.B
      io.boot.bits.wdata := "h0000006F".U(32.W)
      when(padCnt === (ramDepth - 1).U) {
        state := sIdle
        wordAddr := 0.U
      }.otherwise { padCnt := padCnt + 1.U }
    }
    is(sIdle) {
      when(magicLow === "hB007B007".U) { state := sAddr; byteCnt := 0.U }
    }
    is(sAddr) {
      when(byteCnt === 3.U && io.rx_valid) {
        wordAddr := Cat(io.rx_data, shift(31, 8))
        byteCnt := 0.U; state := sSize
      }
    }
    is(sSize) {
      when(byteCnt === 3.U && io.rx_valid) {
        totalWords := nextSize
        wordCnt := 0.U; byteCnt := 0.U
        when(nextSize === 0.U) { state := sDone }.otherwise { state := sData }
      }
    }
    is(sData) {
      when(byteCnt === 3.U && io.rx_valid) {
        io.boot.valid := true.B
        wordCnt := wordCnt + 1.U
        wordAddr := wordAddr + 1.U
        byteCnt := 0.U
        when(wordCnt + 1.U === totalWords) { state := sDone }
      }
    }
    is(sDone) { magicLow := 0.U; state := sIdle }
  }

}
