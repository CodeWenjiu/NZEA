package BASYS

import chisel3._
import chisel3.util._

class II_3 extends Module {
    val io = IO(new Bundle{
        val hsync, vsync = Output(Bool())
        val rgb = Output(UInt(12.W))
        val sw  = Input(Bool())
    })
    val clock_devider = Module(new clock_devider(4))
    clock_devider.io.clk_in := clock
    clock_devider.io.reset := reset

    val vga_sync = Module(new vga_sync)
    vga_sync.clock := clock_devider.io.clk_out.asClock
    vga_sync.reset := reset
    
    io.hsync := vga_sync.io.hsync
    io.vsync := vga_sync.io.vsync

    val key = Module(new key)
    key.io.key_in := io.sw

    val s_raw :: s_pitch :: Nil = Enum(2)
    val state = RegInit(s_raw)

    state := MuxLookup(state, s_raw)(Seq(
        s_raw -> Mux(key.io.is_key_posedge, s_pitch, s_raw),
        s_pitch -> Mux(key.io.is_key_posedge, s_raw, s_pitch)
    ))

    when(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 80.U, vga_sync.Ctrl.yaddr < 60.U)){
        io.rgb := "hF00".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 160.U, vga_sync.Ctrl.yaddr < 120.U)){
        io.rgb := "h00F".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 240.U, vga_sync.Ctrl.yaddr < 180.U)){
        io.rgb := "h0F0".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 320.U, vga_sync.Ctrl.yaddr < 240.U)){
        io.rgb := "h00F".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 400.U, vga_sync.Ctrl.yaddr < 300.U)){
        io.rgb := "hF00".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 480.U, vga_sync.Ctrl.yaddr < 360.U)){
        io.rgb := "h0F0".U
    }.elsewhen(Mux(state === s_raw, vga_sync.Ctrl.xaddr < 560.U, vga_sync.Ctrl.yaddr < 420.U)){
        io.rgb := "hF00".U
    }.otherwise{
        io.rgb := "h00F".U
    }
}
