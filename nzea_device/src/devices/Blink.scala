package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** How wide the blink counter is: the LED toggles every `2^(width-1)` cycles, so the width sets the blink rate. `duty`
  * is the lit fraction: 1 is the plain blinker, and below 1 dims the LED with a PWM window on the lit phase.
  */
case class BlinkParameter(width: Int, duty: Double) extends Parameter:
    require(width >= 2, s"width must be >= 2, got $width")
    require(duty >= 0.0 && duty <= 1.0, s"duty must be in [0, 1], got $duty")

given upickle.default.ReadWriter[BlinkParameter] = upickle.default.macroRW

class BlinkLayers(parameter: BlinkParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The blinker's ports: its own clock and active-low reset, and the LED's pin level (active-low — the pin sinks the
  * current, so a zero lights the LED).
  */
class BlinkIO(parameter: BlinkParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val led = Aligned(Bool())

    /** The counter itself, so the board's instrumentation can watch the slowest bits of a domain's time base. */
    val count = Aligned(UInt(parameter.width))

class BlinkProbe(parameter: BlinkParameter) extends DVBundle[BlinkParameter, BlinkLayers](parameter)

/** A free-running counter whose top bit is the LED: the bring-up design a board can be checked with before anything
  * else exists, and the simplest consumer of a clock domain. Each domain gets its own instance, so a board where only
  * one LED blinks points at the other domain's clocking.
  *
  * A duty below 1 dims the LED: the lit phase is gated by a PWM window of `1 << PwmBits` counter cycles, and the duty
  * is the window's on fraction, quantized to one step per count. The window counts the module's own clock — no
  * frequency is known here, and none is asked for.
  */
@generator
object Blink extends Generator[BlinkParameter, BlinkLayers, BlinkIO, BlinkProbe]:
    override def moduleName(parameter: BlinkParameter): String = "Blink"

    /** The PWM window's width: the duty resolves to `1 << PwmBits` equal steps. */
    private val PwmBits = 8

    def architecture(parameter: BlinkParameter) =
        val io = summon[Interface[BlinkIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.resetN)

        // The counter's carry is the whole width, so the shaped adder keeps the mapper from decomposing it into a ripple
        // ([[constAdder]]'s why).
        val count = RegInit(0.U(parameter.width))
        count := constAdder(count, 1, parameter.width)
        io.count := count

        if parameter.duty >= 1.0 then
            // Full duty is the plain blinker, and nothing else is built: the lit phase is the top bit, held.
            io.led := !Node(count.asBits).bit(parameter.width - 1)
        else
            // The dimming: a window of its own — a free-running `PwmBits` counter — not the blink counter's low bits,
            // which are phase-locked to the toggle and, for a window the toggle's period does not divide, would dim by
            // the overlap rather than by the duty. `threshold` is the duty quantized to one step per window count;
            // the window is the whole time base, so no frequency enters here.
            val window = RegInit(0.U(PwmBits))
            window := constAdder(window, 1, PwmBits)
            val threshold = math.floor(parameter.duty * (1 << PwmBits)).toInt
            val on = window < threshold.U(PwmBits)
            io.led := !(Node(count.asBits).bit(parameter.width - 1) & on)

        // The counter is this module's signal, so it declares itself watchable here: the declaration keeps a wire and
        // marks it for the netlist debug flow (`nzea_rtl.probe.Probes`; whether a level consumes the table is that
        // level's business). Whether an instance is watched is the parent's call — this body is shared by every
        // instance, so only the parent knows which one a probe should mean.
        Probes.declare("count")(count)
