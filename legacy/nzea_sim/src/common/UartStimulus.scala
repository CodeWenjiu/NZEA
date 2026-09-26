package nzea_sim

import chisel3._
import chisel3.util._

class BaudGen(div: Int) extends Module {
  val io = IO(new Bundle { val tick = Output(Bool()) })
  val cnt = RegInit(0.U(log2Ceil(div + 1).W))
  val tick = cnt === (div - 1).U
  when(tick) { cnt := 0.U }.otherwise { cnt := cnt + 1.U }
  io.tick := tick
}

/** UART transmitter stimulus: sends pre-loaded bytes at given baud rate. On `start` pulse, loads the frame and begins
  * sending.
  */
class UartStimulus(baudDiv: Int, maxBytes: Int) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val bytes = Input(Vec(maxBytes, UInt(8.W)))
    val count = Input(UInt((log2Ceil(maxBytes) + 1).W))
    val txd = Output(Bool())
    val done = Output(Bool())
  })

  val baud = Module(new BaudGen(baudDiv))
  val byteIdx = RegInit(0.U((log2Ceil(maxBytes) + 1).W))
  val bitCnt = RegInit(0.U(4.W)) // 0..9 (10 bits per frame)
  val frame = RegInit(0.U(10.W)) // {stop=1, data[7:0], start=0}
  val nextByte = Wire(UInt(8.W))

  // FSM: idle → sending (10 bit-times) → next byte or idle
  val sIdle :: sSending :: Nil = Enum(2)
  val state = RegInit(sIdle)

  io.txd := Mux(state === sIdle, 1.U, frame(0))
  io.done := state === sIdle && RegNext(byteIdx >= io.count && byteIdx =/= 0.U)

  nextByte := io.bytes((byteIdx + 1.U)(log2Ceil(maxBytes) - 1, 0))

  // Load frame on start or between bytes
  when(state === sIdle && io.start) {
    state := sSending
    frame := Cat(1.U(1.W), io.bytes(byteIdx(log2Ceil(maxBytes) - 1, 0)), 0.U(1.W))
    bitCnt := 0.U
    byteIdx := 0.U
  }

  // Shift out bits at baud rate
  when(state === sSending && baud.io.tick) {
    frame := Cat(1.U(1.W), frame(9, 1))
    bitCnt := bitCnt + 1.U
    when(bitCnt === 9.U) {
      when(byteIdx + 1.U >= io.count) {
        state := sIdle
      }.otherwise {
        // Load next frame
        byteIdx := byteIdx + 1.U
        frame := Cat(1.U(1.W), nextByte, 0.U(1.W))
      }
    }
  }

}

/** UART receiver monitor: captures bytes from `rxd`. */
class UartMonitor(baudDiv: Int) extends Module {

  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val valid = Output(Bool())
    val bits = Output(UInt(8.W))
  })

  val baud = Module(new BaudGen(baudDiv))
  val sIdle :: sWaitHalf :: sSampling :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val bitIdx = RegInit(0.U(3.W))
  val halfCnt = RegInit(0.U(log2Ceil(baudDiv / 2 + 1).W))
  val shiftReg = RegInit(0.U(8.W))

  io.valid := false.B
  io.bits := shiftReg

  val rxdPrev = RegNext(io.rxd)
  val startDetected = rxdPrev && !io.rxd

  when(state === sIdle && startDetected) {
    state := sWaitHalf
    halfCnt := 0.U
  }

  when(state === sWaitHalf) {
    when(halfCnt === ((baudDiv / 2) - 1).U) {
      state := sSampling
      bitIdx := 0.U
    }.otherwise { halfCnt := halfCnt + 1.U }
  }

  when(state === sSampling && baud.io.tick) {
    shiftReg := Cat(io.rxd, shiftReg(7, 1))
    bitIdx := bitIdx + 1.U
    when(bitIdx === 7.U) {
      io.valid := true.B
      state := sIdle
    }
  }

}
