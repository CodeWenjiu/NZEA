package ssrc.Zyqn.key

import chisel3._
import chisel3.util._

class keyIO extends Bundle{
    val key = Input(Bool())
}

class Debuncer(Freq_clock: Int, Debunce_time: Int) extends Module{
    val io = IO(new Bundle{
        val key = Input(Bool())
        val Fo = Output(Bool())
    })

    val chasig = Freq_clock * Debunce_time / 1000 // delay ms

    val counter = RegInit(0.U(log2Ceil(chasig).W))
    val Iv = RegInit(false.B)
    when(io.key === Iv){
        counter := Mux(counter === (chasig - 1).U, 0.U, counter + 1.U)
    }.otherwise{
        counter := 0.U
        Iv := io.key
    }

    io.Fo := RegEnable(io.key, counter === (chasig - 1).U)
}

class key(Freq_clock: Int, Debunce_time: Int) extends Module{
    val io = IO(new keyIO)
    val pressed = IO(Output(Bool()))

    val key_debouncer = Module(new Debuncer(Freq_clock, Debunce_time))
    key_debouncer.io.key := io.key

    pressed := RegNext(key_debouncer.io.Fo) && !key_debouncer.io.Fo
}
