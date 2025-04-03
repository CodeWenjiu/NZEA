package BASYS

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

class Debouncer(val COUNT_MAX : Int) extends Module {
    val io = IO(new Bundle{
        val input = Input(Bool())
        val output = Output(Bool())
    })

    val count = RegInit(0.U(log2Ceil(COUNT_MAX+1).W))

    val Iv = RegInit(false.B)

    val Output_filiter = RegInit(false.B)
    io.output := Output_filiter

    when(io.input === Iv){
        count := count + 1.U
        when(count === (COUNT_MAX-1).U){
            Output_filiter := io.input
        }
    }.otherwise{
        count := 0.U
        Iv := io.input
    }
}