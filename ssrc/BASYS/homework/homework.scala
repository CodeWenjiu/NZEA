package homework

import timer._
import BASYS._

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

class Homework extends Module {
    val io = IO(new Bundle{
        val ps2_clk = Input(Bool())
        val ps2_data = Input(Bool())
        val sw1 = Input(Bool())
        val clear = Input(Bool())
        val stop = Input(Bool())
        val up_or_down = Input(Bool())
        val out = Output(UInt(8.W))
        val bit = Output(UInt(4.W))
    })

    val s_second :: s_minute :: s_10micro :: Nil = Enum(3)
    val state = RegInit(s_second)

    val key_1 = Module(new key)
    val is_key_posedge = Wire(Bool())
    key_1.io.key_in := io.sw1
    is_key_posedge := key_1.io.is_key_posedge

    val ps2 = Module(new ps2mouse)
    ps2.io.clock := clock
    ps2.io.reset := reset
    ps2.io.ps2_clk := io.ps2_clk
    ps2.io.ps2_data := io.ps2_data

    when(is_key_posedge) {
        state := MuxLookup(state, s_second)(Seq(
            s_second -> s_minute,
            s_minute -> s_10micro,
            s_10micro -> s_second
        ))
    }

    val total_10m_seconds = Wire(UInt(32.W))
    val total_seconds = Wire(UInt(32.W))
    val total_minutes = Wire(UInt(32.W))

    val timer = Module(new Timer(100))
    timer.io.clear <> io.clear
    timer.io.stop <> io.stop
    timer.io.up_or_down <> io.up_or_down
    total_10m_seconds <> timer.io.time_10m_seconds
    total_seconds := total_10m_seconds/100.U
    total_minutes := total_seconds/60.U

    val time_type_Choice = MuxLookup(state, total_seconds) (Seq(
        s_second -> total_seconds%60.U,
        s_minute -> total_minutes,
        s_10micro -> total_10m_seconds%99.U
    ))

    val Four_key_seg = Module(new Four_key_seg)
    Four_key_seg.io.num := time_type_Choice
    io.out := Four_key_seg.io.seg_led
    io.bit := Four_key_seg.io.seg_sel
}
