package ssrc.Tangnano

import chisel3._
import chisel3.util._

class top extends Module {
    val io = IO(new Bundle{
        val led = Output(Bool())
    })

    val blink = Module(new led.blink(100_000_000, 1))
    io <> blink.io
}
