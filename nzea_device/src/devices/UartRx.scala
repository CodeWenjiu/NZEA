package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.probe.Probes
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The receiver's two rates: the clock it runs on and the line's rate. */
case class UartRxParameter(clockHz: Int, lineHz: Int) extends Parameter:
    require(clockHz > 0, s"clockHz must be positive, got $clockHz")
    require(lineHz > 0, s"lineHz must be positive, got $lineHz")

    // A bit's centre is found by counting half a period from the start edge, so a bit period of at least four cycles is
    // what keeps that count a place inside the bit.
    require(
        clockHz / lineHz >= 4,
        s"a bit period of ${clockHz / lineHz} cycles is too short to sample its centre (need >= 4)"
    )

given upickle.default.ReadWriter[UartRxParameter] = upickle.default.macroRW

class UartRxLayers(parameter: UartRxParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The receiver's ports: the line it watches and the byte it hands out. `valid` is one cycle wide and every byte is
  * taken — a loader consumes whatever arrives, in step or out of it — so there is no ready.
  */
class UartRxIO(parameter: UartRxParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val rxd = Flipped(Bool())
    val data = Aligned(UInt(8))
    val valid = Aligned(Bool())

class UartRxProbe(parameter: UartRxParameter) extends DVBundle[UartRxParameter, UartRxLayers](parameter)

/** The line receiver: 8N1, LSB first, every bit sampled at its centre.
  *
  * The line is asynchronous to the fabric, so two flops bring it into this domain; the sampling counter then starts at
  * half a bit period from the start edge, which is what puts the samples at the bits' middles instead of their edges. A
  * byte is handed out only once the stop bit has been sampled high — a low one is a framing error and the byte is
  * dropped, and the state machine is back to scanning for the next start edge.
  *
  * Written from the board's legacy receiver (the `uart_lite` design in tag `legacy-baseline`), the one the board's own
  * loads were validated with.
  */
@generator
object UartRx extends Generator[UartRxParameter, UartRxLayers, UartRxIO, UartRxProbe]:
    override def moduleName(parameter: UartRxParameter): String = "UartRx"

    def architecture(parameter: UartRxParameter) =
        val io = summon[Interface[UartRxIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.resetN)

        val bitPeriod = parameter.clockHz / parameter.lineHz
        val halfPeriod = bitPeriod / 2
        val periodBits = 32 - Integer.numberOfLeadingZeros(bitPeriod)

        val counter = RegInit(0.U(periodBits))
        val sampleTick = counter === 0.U(periodBits)

        val sync0 = RegInit(true.B)
        val rxd = RegInit(true.B)
        sync0 := io.rxd
        rxd := sync0

        val busy = RegInit(false.B)
        val shift = RegInit(0.U(8))
        val bit = RegInit(0.U(4))
        val outValid = RegInit(false.B)
        val outData = RegInit(0.U(8))

        // Half a bit while idle, so the first tick after a start edge lands at that bit's centre; a whole period after
        // every further tick; zero once the stop bit is done, where the counter waits for the next start edge.
        when(!busy) {
            counter := halfPeriod.U(periodBits)
        }.otherwise {
            when(!sampleTick) {
                counter := prefixSub(counter, 1.U(periodBits), periodBits)._1
            }.otherwise {
                counter := (bit === 9.U(4)) ? (0.U(periodBits), bitPeriod.U(periodBits))
            }
        }

        when(busy & sampleTick) {
            when(bit === 9.U(4)) {
                busy := false.B
            }.otherwise {
                when(bit === 0.U(4)) {
                    // The start bit's centre: a high sample was a glitch on the line, not a start.
                    when(rxd) {
                        busy := false.B
                    }
                }.otherwise {
                    shift := (rxd.asBits ## Node(shift.asBits).bits(7, 1)).asUInt
                }
            }
        }.otherwise {
            when(!busy & !rxd) {
                busy := true.B
                shift := 0.U(8)
            }
        }

        when(!busy) {
            bit := 0.U(4)
        }.otherwise {
            when(busy & sampleTick) {
                bit := constAdder(bit, 1, 4)
            }
        }

        when(outValid) {
            outValid := false.B
        }
        when(busy & sampleTick & (bit === 9.U(4)) & rxd) {
            outData := shift
            outValid := true.B
        }

        io.data := outData
        io.valid := outValid

        Probes.declare("data")(outData)
        Probes.declare("valid")(outValid)
