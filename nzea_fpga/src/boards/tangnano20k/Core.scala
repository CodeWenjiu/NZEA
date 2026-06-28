package nzea_fpga.boards.tangnano20k

import chisel3._
import chisel3.util._
import nzea_device.uart.{UartRx, UartTx}

/** Minimal test core — no SRAM, no BootFsm.
  *
  *   - LED0: ~1.5 Hz alive blink (bit 25 of 26-bit counter)
  *   - LED1: switch state
  *   - LED2: UART RX activity (stretched)
  *   - LED3: UART TX ready
  *   - LED4–5: reserved
  *   - UART: echo — received byte sent back
  */
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

  // UART echo — received byte loops back
  uartTx.io.in.valid := uartRx.io.out.valid
  uartTx.io.in.bits := uartRx.io.out.bits

  // Alive blink (~1.5 Hz at 100 MHz)
  val blinkCnt = RegInit(0.U(26.W))
  blinkCnt := blinkCnt + 1.U

  // RX-activity stretch (~1 s visible pulse)
  val rxStretch = RegInit(0.U(log2Ceil(clkFreq + 1).W))
  when(uartRx.io.out.valid) { rxStretch := (clkFreq - 1).U }
  when(rxStretch > 0.U) { rxStretch := rxStretch - 1.U }

  io.led := Cat(0.U(2.W), uartTx.io.in.ready, rxStretch > 0.U, io.switch, blinkCnt(25))
}
