package timer

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

class Timer(val clk_Mhz: Int) extends Module {
    val io = IO(new Bundle {
        val time_10m_seconds = Output(UInt(32.W))
        val clear = Input(Bool())
        val stop = Input(Bool())
        val up_or_down = Input(Bool())
    })

    val timer_counter = RegInit(0.U(32.W))

    val total_10m_seconds = RegInit(0.U(32.W))

    timer_counter := timer_counter + 1.U

    when(timer_counter === ((clk_Mhz * 1024 * 1024)/100 - 1).U) {
        timer_counter := 0.U
        when(!io.clear){
            total_10m_seconds := 0.U
        }.elsewhen(!io.stop){
            total_10m_seconds := total_10m_seconds
        }.elsewhen(io.up_or_down){
            total_10m_seconds := total_10m_seconds + 1.U
        }.otherwise{
            when(total_10m_seconds === 0.U){
                total_10m_seconds := 0.U
            }.otherwise{
                total_10m_seconds := total_10m_seconds - 1.U
            }
        }
    }

    io.time_10m_seconds := total_10m_seconds
}