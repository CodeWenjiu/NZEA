package ssrc.Zyqn

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import freechips.rocketchip.diplomacy.LazyModule

class top extends Module {
    val led_io = IO(new ssrc.Zyqn.led.blinkIO)

    withReset(!(reset.asBool)){
        val deFreq = Module(new ssrc.Zyqn.led.clk_deFreq(50_000_000, 256))
        val dutyM = withClock(deFreq.io.clock.asClock)(RegInit(0.U(9.W)))
        dutyM := dutyM + 1.U

        val pwm = Module(new ssrc.Zyqn.led.pwm(50_000_000, 10_000))
        pwm.io.duty := Mux(dutyM(8), dutyM(7, 0), ~dutyM(7, 0))
        led_io.led := pwm.io.pwm
    }
}
