package BASYS

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

class key extends Module {
    val io = IO(new Bundle {
        val key_in = Input(Bool())
        val is_key_posedge = Output(Bool())
    })

    val key_in_filited = Wire(Bool())
    val key_in_debouncer = Module(new Debouncer(10000))

    key_in_debouncer.io.input := io.key_in
    key_in_filited := key_in_debouncer.io.output

    val key_in_pre = RegInit(true.B)

    key_in_pre := key_in_filited

    when(key_in_pre === false.B && key_in_filited === true.B) {
        io.is_key_posedge := true.B
    }.otherwise {
        io.is_key_posedge := false.B
    }

}