package nzea_fpga.boards.tangnano20k

import chisel3._
import chisel3.util.{is, log2Ceil, switch, Cat}
import nzea_device.uart.{UartRx, UartTx}
import nzea_tile.platform.BootFsm

class TangNano20kCore(clkFreq: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val switch = Input(Bool())
    val led = Output(UInt(6.W))
    val uart_tx = Output(Bool())
    val uart_rx = Input(Bool())
  })

  val uartTx = Module(new UartTx(clkFreq, baudRate))
  val uartRx = Module(new UartRx(clkFreq, baudRate))
  io.uart_tx := uartTx.io.txd
  uartRx.io.rxd := io.uart_rx
  uartRx.io.out.ready := true.B

  val bootFsm = Module(new BootFsm)
  val ramSize = 1024

  require(
    bootFsm.mrom.depth <= ramSize,
    s"hex has ${bootFsm.mrom.depth} words, RAM holds $ramSize"
  )

  bootFsm.io.boot_en := true.B
  bootFsm.io.rx_valid := uartRx.io.out.valid
  bootFsm.io.rx_data := uartRx.io.out.bits

  val ram = SyncReadMem(ramSize, UInt(32.W))
  val wrAddr = bootFsm.io.boot.bits.addr(9, 0)
  when(bootFsm.io.boot.valid) { ram.write(wrAddr, bootFsm.io.boot.bits.wdata) }

  val prevCpuReset = RegNext(bootFsm.io.cpu_reset)
  val cpuResetFell = prevCpuReset && !bootFsm.io.cpu_reset
  val bootPhase = RegInit(0.U(2.W))
  when(cpuResetFell) { bootPhase := bootPhase + 1.U }
  val bootDone = bootPhase >= 2.U

  object State extends ChiselEnum {
    val WaitBoot, ReadWord, WaitRead, SendBytes, Done = Value
  }

  import State._
  val state = RegInit(WaitBoot)
  val rdAddr = RegInit(0.U(10.W))
  val byteIdx = RegInit(0.U(2.W))
  val passWords = RegInit(0.U(10.W))
  val rdData = ram.read(rdAddr)

  uartTx.io.in.valid := false.B
  uartTx.io.in.bits := DontCare

  switch(state) {
    is(WaitBoot) {
      when(bootDone) {
        state := ReadWord
        rdAddr := 0.U
        passWords := 16.U
      }
    }
    is(ReadWord) { state := WaitRead }
    is(WaitRead) { state := SendBytes; byteIdx := 0.U }
    is(SendBytes) {
      uartTx.io.in.valid := true.B
      uartTx.io.in.bits := (rdData >> (byteIdx * 8.U))(7, 0)
      when(uartTx.io.in.ready) {
        when(byteIdx === 3.U) {
          when(passWords === 1.U) {
            state := Done
          }.otherwise {
            rdAddr := rdAddr + 1.U
            passWords := passWords - 1.U
            state := ReadWord
          }
        }.otherwise { byteIdx := byteIdx + 1.U }
      }
    }
    is(Done) { state := WaitBoot; bootPhase := 1.U }
  }

  val rxStretch = RegInit(0.U(log2Ceil(clkFreq + 1).W))
  when(uartRx.io.out.valid) { rxStretch := (clkFreq - 1).U }
  when(rxStretch > 0.U) { rxStretch := rxStretch - 1.U }

  io.led := Cat(
    state === Done,
    state === SendBytes,
    bootDone,
    bootPhase === 1.U,
    bootFsm.io.cpu_reset,
    rxStretch > 0.U
  )

}
