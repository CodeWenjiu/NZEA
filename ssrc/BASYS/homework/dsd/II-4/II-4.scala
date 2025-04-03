package BASYS

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

object II_4_state extends ChiselEnum {
    val s_idle, s_frame_f, s_frame_s = Value
}

class II_4 extends Module {
    val io = IO(new Bundle{
        val sync = new VGASyncIO
        val rgb = Output(UInt(12.W))
        val ps2_clk = Analog(1.W)
        val ps2_data = Analog(1.W)
    })

    val vga_sync = Module(new vga_sync)

    io.sync <> vga_sync.io

    val img_pointer = Module(new img_pointer)
    img_pointer.io.vgaCtrl := vga_sync.Ctrl
    img_pointer.io.ps2_clk <> io.ps2_clk
    img_pointer.io.ps2_data <> io.ps2_data

    val state = RegInit(II_4_state.s_idle)

    val (window_match, _) = BASYS_utils.pos_match(20.U, 0.U, 600.U, 480.U, vga_sync.Ctrl.xaddr, vga_sync.Ctrl.yaddr, true.B)
    val (mouse_match_f, _) = BASYS_utils.pos_match(70.U, 140.U, 200.U, 200.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, state === II_4_state.s_idle)
    val (mouse_match_s, _) = BASYS_utils.pos_match(370.U, 140.U, 200.U, 200.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, state === II_4_state.s_idle)

    state := MuxLookup(state, II_4_state.s_frame_f)(Seq(
        II_4_state.s_idle -> Mux(img_pointer.io.Left_click, Mux(mouse_match_f, II_4_state.s_frame_f, Mux(mouse_match_s, II_4_state.s_frame_s, II_4_state.s_idle)), II_4_state.s_idle),
        II_4_state.s_frame_f -> Mux(img_pointer.io.Right_click, II_4_state.s_idle, II_4_state.s_frame_f),
        II_4_state.s_frame_s -> Mux(img_pointer.io.Right_click, II_4_state.s_idle, II_4_state.s_frame_s)
    ))

    val img_a = Module(new image(200, 200, "frame_a"))
    img_a.io.pos_x := Mux(state === II_4_state.s_frame_f, 220.U, 70.U)
    img_a.io.pos_y := 140.U
    img_a.io.vgaCtrl := vga_sync.Ctrl
    img_a.io.ena   := state =/= II_4_state.s_frame_s

    val img_b = Module(new image(200, 200, "frame_b"))
    img_b.io.pos_x := Mux(state === II_4_state.s_frame_s, 220.U, 370.U)
    img_b.io.pos_y := 140.U
    img_b.io.vgaCtrl := vga_sync.Ctrl
    img_b.io.ena   := state =/= II_4_state.s_frame_f

    when(img_pointer.io.hit){
        io.rgb := img_pointer.io.rgb
    }.elsewhen(img_a.io.hit) {
        io.rgb := img_a.io.rgb
    }.elsewhen(img_b.io.hit){
        io.rgb := img_b.io.rgb
    }.elsewhen(window_match){
        io.rgb := "hfff".U
    }.otherwise{
        io.rgb := "h000".U
    }
}
