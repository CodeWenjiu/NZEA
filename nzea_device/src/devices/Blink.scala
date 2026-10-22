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

/** The blinker's ports: its own clock and active-low reset, the LED's pin level (active-low — the pin sinks the
  * current, so a zero lights the LED), and the level that holds the lit phase on.
  */
class BlinkIO(parameter: BlinkParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** Held high, the LED stops blinking and stays in its lit phase; the dimming window still shapes it, so a steady
      * LED is as bright as a blinking one's lit phase.
      */
    val steady = Flipped(Bool())

    val led = Aligned(Bool())

    /** The counter itself, so the board's instrumentation can watch the slowest bits of a domain's time base. */
    val count = Aligned(UInt(parameter.width))

class BlinkProbe(parameter: BlinkParameter) extends DVBundle[BlinkParameter, BlinkLayers](parameter)

/** A free-running counter whose top bit is the LED: the bring-up design a board can be checked with before anything
  * else exists. Each domain gets its own instance, so a board where only one LED blinks points at the other domain's
  * clocking. A duty below 1 dims the LED: the lit phase is gated by a PWM window of `1 << PwmBits` counter cycles, and
  * the window counts the module's own clock — no frequency is known here, and none is asked for. `steady` holds the lit
  * phase on, so a parent that has something to say with the LED says it in the panel's own terms rather than by tying
  * the pin low (which would be full brightness, not the duty the panel was given).
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

        // What the LED shows: the counter's top bit, held on by `steady` — steady is the lit phase made permanent, not a
        // second level, so the window below keeps running and the brightness does not jump when a parent asserts it.
        val lit = Node(count.asBits).bit(parameter.width - 1) | io.steady

        if parameter.duty >= 1.0 then
            // Full duty is the plain blinker: the lit phase is the top bit, and no PWM window is built.
            io.led := !lit
        else
            // The dimming: a window of its own — a free-running `PwmBits` counter — not the blink counter's low bits,
            // which are phase-locked to the toggle and, for a window the toggle's period does not divide, would dim by
            // the overlap rather than by the duty. No frequency enters here: the window is the whole time base.
            val window = RegInit(0.U(PwmBits))
            window := constAdder(window, 1, PwmBits)
            val threshold = math.floor(parameter.duty * (1 << PwmBits)).toInt
            val on = window < threshold.U(PwmBits)
            io.led := !(lit & on)

        // The counter declares itself watchable here: the declaration keeps a wire and marks it for the netlist debug
        // flow (`nzea_rtl.probe.Probes`). Whether an instance is watched is the parent's call — this body is shared by
        // every instance, so only the parent knows which one a probe should mean.
        Probes.declare("count")(count)
