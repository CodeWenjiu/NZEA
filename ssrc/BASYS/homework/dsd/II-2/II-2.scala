package homework

import chisel3._
import chisel3.util._
import BASYS._

class II_2 extends Module {
    val io = IO(new Bundle {
        val change = Input(Bool())
        val write = Input(Bool())
        val read = Input(Bool())

        val seg_sel = Output(UInt(4.W))
        val seg_led = Output(UInt(8.W))

        val full = Output(Bool())
        val empty = Output(Bool())
    })

    val key_change = Module(new key)
    val key_write = Module(new key)
    val key_read = Module(new key)

    val flag_change = Wire(Bool())
    val flag_write = Wire(Bool())
    val flag_read = Wire(Bool())

    key_change.io.key_in := io.change
    key_write.io.key_in := io.write
    key_read.io.key_in := io.read

    flag_change := key_change.io.is_key_posedge
    flag_write := key_write.io.is_key_posedge
    flag_read := key_read.io.is_key_posedge

    val num_write = RegInit(0.U(4.W))
    val num_read = RegInit(0.U(4.W))

    val FIFO = Module(new FIFO(4))
    FIFO.io.clk := clock
    FIFO.io.srst := reset.asBool
    FIFO.io.din := num_write
    FIFO.io.wr_en := flag_write
    FIFO.io.rd_en := flag_read

    when(flag_change){
        when(num_write === 9.U){
            num_write := 0.U
        }otherwise{
            num_write := num_write + 1.U
        }
    }

    val flag_read_delay = RegInit(false.B)
    flag_read_delay := flag_read
    when(flag_read === false.B && flag_read_delay === true.B){
        num_read := FIFO.io.dout
    }

    val BCD_write = Module(new BCDDecoder)
    val BCD_read = Module(new BCDDecoder)

    BCD_write.io.in := num_write
    BCD_read.io.in := num_read

    val sel_change = Reg(Bool())
    val sel_cnt = RegInit(0.U(10.W))

    when(sel_cnt === 1023.U){
        sel_cnt := 0.U
        sel_change := !sel_change
    }.otherwise{
        sel_cnt := sel_cnt + 1.U
    }

    when(sel_change === true.B){
        io.seg_sel := "b1101".U
        io.seg_led := ~BCD_write.io.out
    }.otherwise{
        io.seg_sel := "b1110".U
        io.seg_led := ~BCD_read.io.out
    }

    io.full := FIFO.io.full
    io.empty := FIFO.io.empty
}
