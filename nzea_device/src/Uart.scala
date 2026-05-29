package nzea_device

import chisel3._
import chisel3.util.Decoupled

/** Full-duplex UART (8N1). Combines [[UartTx]] and [[UartRx]].
  *
  * @param clockHz  system clock in Hz
  * @param baudRate baud rate
  */
class Uart(clockHz: Int, baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val txd = Output(Bool())
    val rxd = Input(Bool())
    val tx  = Flipped(Decoupled(UInt(8.W)))
    val rx  = Decoupled(UInt(8.W))
  })

  val tx = Module(new UartTx(clockHz, baudRate))
  val rx = Module(new UartRx(clockHz, baudRate))

  io.txd     := tx.io.txd
  tx.io.in   <> io.tx

  rx.io.rxd  := io.rxd
  io.rx      <> rx.io.out
}
