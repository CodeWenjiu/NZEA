package BASYS

import chisel3._
import chisel3.util._

class clock_devider(val mod: Int) extends RawModule {
    val io = IO(new Bundle{
        val clk_in = Input(Clock())
        val reset = Input(Reset())
        val clk_out = Output(Bool())
    })

    withClockAndReset(io.clk_in, io.reset){
        val counter = RegInit(0.U(log2Ceil(mod).W))
        when(counter === (mod - 1).U){
            counter := 0.U
        }.otherwise{
            counter := counter + 1.U
        }

        io.clk_out := Mux(counter < (mod / 2).U, true.B, false.B)
    }
}
