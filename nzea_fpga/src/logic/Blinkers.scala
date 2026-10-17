package nzea_fpga.logic

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.devices.{Blink, BlinkParameter}
import nzea_rtl.probe.Probes
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** How the two blinkers are built: the counter width — one toggle per `2^(width-1)` cycles of its own domain — and the
  * lit fraction (`duty`: 1 is the plain toggle, below 1 dims the LED with a PWM window of its own).
  */
case class BlinkersParameter(width: Int, duty: Double) extends Parameter

given upickle.default.ReadWriter[BlinkersParameter] = upickle.default.macroRW

class BlinkersLayers(parameter: BlinkersParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class BlinkersIO(parameter: BlinkersParameter) extends HWBundle(parameter):
    val clock50 = Flipped(Clock())
    val resetN50 = Flipped(Reset())
    val clock100 = Flipped(Clock())
    val resetN100 = Flipped(Reset())
    val led1 = Aligned(Bool())
    val led2 = Aligned(Bool())

class BlinkersProbe(parameter: BlinkersParameter) extends DVBundle[BlinkersParameter, BlinkersLayers](parameter)

/** The board's two LED indicators, one per clock domain *on purpose*: LED1 runs off the oscillator's own 50 MHz, LED2
  * off the wizard's 100 MHz, so a board where only LED1 blinks says the wizard's clock path — or its IP — is at fault.
  * A capture tells the same story without eyes: the domain whose counter stands still is the domain whose clocking is
  * at fault.
  *
  * The counters are declared watchable in `Blink`'s body but bound here, where the instances are named — a shared
  * module body cannot know which instance a probe should mean.
  */
@generator
object Blinkers extends Generator[BlinkersParameter, BlinkersLayers, BlinkersIO, BlinkersProbe]:
    override def moduleName(parameter: BlinkersParameter): String = "Blinkers"

    def architecture(parameter: BlinkersParameter) =
        val io = summon[Interface[BlinkersIO]]
        val blink50 = Blink.instantiate(BlinkParameter(parameter.width, parameter.duty))
        blink50.io.clock := io.clock50
        blink50.io.resetN := io.resetN50
        io.led1 := blink50.io.led
        val blink100 = Blink.instantiate(BlinkParameter(parameter.width, parameter.duty))
        blink100.io.clock := io.clock100
        blink100.io.resetN := io.resetN100
        io.led2 := blink100.io.led
        Probes.watch(blink50, "count")
        Probes.watch(blink100, "count")
