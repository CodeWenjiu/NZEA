package BASYS

import chisel3._
import chisel3.util._

class FIFO(width: Int) extends BlackBox{
    val io = IO(new Bundle{
        val clk = Input(Clock())
        val srst = Input(Reset())
        val din = Input(UInt(width.W))
        val wr_en = Input(Bool())
        val rd_en = Input(Bool())
        val dout = Output(UInt(width.W))
        val full = Output(Bool())
        val empty = Output(Bool())
    })
}
