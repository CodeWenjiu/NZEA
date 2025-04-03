package ssrc.Tangnano.led

import chisel3._
import chisel3.util._

class blink(Freq_clock: Int, Freq_blink: Int) extends Module {
    val io = IO(new Bundle{
        val led = Output(Bool())
    })

    assert(Freq_blink < Freq_clock / 2, "Blink frequency must be less than clock frequency")
    val chasig = Freq_clock / Freq_blink / 2

    val counter = RegInit(0.U(log2Ceil(chasig).W))

    counter := Mux(counter === (chasig - 1).U, 0.U, counter + 1.U)
    io.led := RegEnable(~io.led, counter === 0.U)
}
