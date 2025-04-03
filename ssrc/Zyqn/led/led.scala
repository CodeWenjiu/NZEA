package ssrc.Zyqn.led

import chisel3._
import chisel3.util._
import firrtl2.getWidth

class blinkIO extends Bundle {
    val led = Output(Bool())
}

class clk_deFreq(Freq_clock: Int, Freq_deFreq: Int) extends Module {
    val io = IO(new Bundle{
        val clock = Output(Bool())
    })

    assert(Freq_deFreq < Freq_clock / 2, "DeFreq frequency must be less than clock frequency")
    val chasig = Freq_clock / Freq_deFreq / 2

    val counter = RegInit(0.U(log2Ceil(chasig).W))

    counter := Mux(counter === (chasig - 1).U, 0.U, counter + 1.U)
    io.clock.asBool := RegEnable(~(io.clock).asBool, counter === 0.U)
}

class blink(Freq_clock: Int, Freq_blink: Int) extends Module {
    val io = IO(new blinkIO)

    assert(Freq_blink < Freq_clock / 2, "Blink frequency must be less than clock frequency")
    val chasig = Freq_clock / Freq_blink / 2

    val counter = RegInit(0.U(log2Ceil(chasig).W))

    counter := Mux(counter === (chasig - 1).U, 0.U, counter + 1.U)
    io.led := RegEnable(~io.led, counter === 0.U)
}

class pwmIO extends Bundle {
    val duty = Input(UInt(8.W))
    val pwm = Output(Bool())
}
class pwm(Freq_clock: Int, Freq_pwm: Int) extends Module {
    val io = IO(new pwmIO)

    val deFreq = Module(new clk_deFreq(Freq_clock, Freq_pwm * math.pow(2, io.duty.getWidth).toInt))

    val duty_cycle = withClock(deFreq.io.clock.asClock)(RegInit(0.U(io.duty.getWidth.W)))
    duty_cycle := duty_cycle + 1.U

    io.pwm := duty_cycle < io.duty
}

class waterIO extends Bundle {
    val led1 = Output(Bool())
    val led2 = Output(Bool())
}

class water(Freq_clock: Int, Freq_blink: Int) extends Module {
    val io = IO(new waterIO)

    assert(Freq_blink < Freq_clock / 2, "Blink frequency must be less than clock frequency")
    val chasig = Freq_clock / Freq_blink / 2

    val counter = RegInit(0.U(log2Ceil(chasig).W))

    counter := Mux(counter === (chasig - 1).U, 0.U, counter + 1.U)
    
    val water_reg = RegInit(1.U(2.W))
    when(counter === 0.U){
        water_reg := Cat(water_reg(0), water_reg(1))
    }

    io.led1 := water_reg(0)
    io.led2 := water_reg(1)
}
