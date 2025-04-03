package BASYS

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

class BCDDecoder extends Module {
    val io = IO(new Bundle{
        val in = Input(UInt(4.W))
        val out = Output(UInt(8.W))
    })

    val num_0 = "hc0".U(8.W)
    val num_1 = "hf9".U(8.W)
    val num_2 = "ha4".U(8.W)
    val num_3 = "hb0".U(8.W)
    val num_4 = "h99".U(8.W)
    val num_5 = "h92".U(8.W)
    val num_6 = "h82".U(8.W)
    val num_7 = "hf8".U(8.W)
    val num_8 = "h80".U(8.W)
    val num_9 = "h90".U(8.W)
    val num_A = "hc0".U(8.W)
    val num_B = "hc0".U(8.W)
    val num_C = "hc0".U(8.W)
    val num_D = "hc0".U(8.W)
    val num_E = "hc0".U(8.W)
    val num_F = "hc0".U(8.W)

    io.out := MuxLookup(io.in, "hF".U)(Seq(
        "h0".U -> num_0,
        "h1".U -> num_1,
        "h2".U -> num_2,
        "h3".U -> num_3,
        "h4".U -> num_4,
        "h5".U -> num_5,
        "h6".U -> num_6,
        "h7".U -> num_7,
        "h8".U -> num_8,
        "h9".U -> num_9,
        "hA".U -> num_A,
        "hB".U -> num_B,
        "hC".U -> num_C,
        "hD".U -> num_D,
        "hE".U -> num_E,
        "hF".U -> num_F
    ))
}

class Four_key_seg extends Module {
    val io = IO(new Bundle{
        val num = Input(UInt(32.W))
        val seg_led = Output(UInt(8.W))
        val seg_sel = Output(UInt(4.W))
    })

    val decoder1 = Module(new BCDDecoder)
    val decoder2 = Module(new BCDDecoder)
    val decoder3 = Module(new BCDDecoder)
    val decoder4 = Module(new BCDDecoder)

    decoder1.io.in := (io.num % 10.U)(3, 0)
    decoder2.io.in := (io.num / 10.U % 10.U)(3, 0)
    decoder3.io.in := (io.num / 100.U % 10.U)(3, 0)
    decoder4.io.in := (io.num / 1000.U % 10.U)(3, 0)
    
    val bit_reg = RegInit("b1110".U(4.W))
    val counter = RegInit(0.U(32.W))
    counter := counter + 1.U
    when(counter === 100000.U) {
        counter := 0.U
        bit_reg := Cat(bit_reg(2, 0), bit_reg(3))
    }

    io.seg_led := MuxLookup(bit_reg, 0.U(8.W)) (Seq(
        "b1110".U -> decoder1.io.out,
        "b1101".U -> decoder2.io.out,
        "b1011".U -> decoder3.io.out,
        "b0111".U -> decoder4.io.out
    ))

    io.seg_sel := bit_reg
}