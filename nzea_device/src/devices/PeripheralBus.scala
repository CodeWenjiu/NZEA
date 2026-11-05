package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The peripheral bus's own rate, as the platform states it: the tile clock, and the clock one device access is paced
  * by. `deviceCycles` is how many of those device clocks one access takes, so an access is `ceil(deviceCycles × clockHz
  * ÷ deviceHz)` bus cycles — the same arithmetic the bridge flow's [[nzea_rtl.bus.Delay]] resolves, which is what lets
  * the two flows be compared.
  */
case class PeripheralBusParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    clockHz: Int,
    deviceHz: Int,
    deviceCycles: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(clockHz >= 1, s"clockHz must be >= 1, got $clockHz")
    require(deviceHz >= 1, s"deviceHz must be >= 1, got $deviceHz")
    require(clockHz % deviceHz == 0, s"the device clock must divide the bus clock: $deviceHz Hz against $clockHz Hz")
    require(deviceCycles >= 1, s"deviceCycles must be >= 1, got $deviceCycles")

    /** Bus cycles between two device clocks: the clock enable's period. */
    val stepCycles: Int = clockHz / deviceHz

    /** Bus cycles one access occupies: the device's own cycles at the enable's pace. */
    val accessCycles: Int = stepCycles * deviceCycles

given upickle.default.ReadWriter[PeripheralBusParameter] = upickle.default.macroRW

class PeripheralBusLayers(parameter: PeripheralBusParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class PeripheralBusIO(parameter: PeripheralBusParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The fabric side: the module is its slave, and the window's whole range decodes to the bank below. */
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class PeripheralBusProbe(parameter: PeripheralBusParameter)
    extends DVBundle[PeripheralBusParameter, PeripheralBusLayers](parameter)

/** A real device behind the peripheral window: a small register bank paced by a divided device clock, so an access
  * lasts the wire's own time instead of a cycle.
  *
  * The clock enable is the division — the module still runs on the tile clock, and a device clock edge is every
  * `clockHz / deviceHz` cycles — which keeps one clock domain and needs no vendor clock primitive. An access is
  * `deviceCycles` of those edges, and the response is offered for exactly one bus cycle afterwards.
  *
  * The bank is a latency target, not a byte-addressable register file: the window is `BankWords` words, addressed by
  * the low address bits and wrapping every `BankWords`, and a write applies the request's byte strobes. A read outside
  * any meaningful register is still answered in the same time — the access, not the contents, is what this device
  * states.
  */
@generator
object PeripheralBus
    extends Generator[PeripheralBusParameter, PeripheralBusLayers, PeripheralBusIO, PeripheralBusProbe]:

    /** Words in the bank. Fixed: the device exists to make a bus access take the bus's own time, and its contents are
      * only what the timing tests read back.
      */
    val BankWords: Int = 16

    /** The module is named by its rate facts — the body is the timing, so one name is one body. */
    override def moduleName(parameter: PeripheralBusParameter): String =
        s"PeripheralBus_d${parameter.deviceHz}x${parameter.deviceCycles}"

    def architecture(parameter: PeripheralBusParameter) =
        val io = summon[Interface[PeripheralBusIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val indexWidth = log2Ceil(BankWords)
        val wordOffsetBits = log2Ceil(parameter.dataWidth / 8)
        val lanes = parameter.dataWidth / 8
        val countWidth = log2Ceil(parameter.accessCycles + 1)

        val bank = Reg(Vec(BankWords, UInt(parameter.dataWidth)))
        when(!io.resetN.asBool) {
            for index <- 0 until BankWords do bank(index) := 0.U(parameter.dataWidth)
        }

        // One access at a time: the port takes the request, holds it off, and offers the answer when the device's time
        // has run out. `remain` counts the bus cycles still to wait, and zero means the answer is due.
        val busy = RegInit(false.B)
        val remain = RegInit(0.U(countWidth))
        val respData = Reg(UInt(parameter.dataWidth))
        val respUser = Reg(UInt(parameter.userWidth))
        val respId = Reg(UInt(parameter.idWidth))

        io.bus.req.ready := !busy
        val fire = io.bus.req.valid & io.bus.req.ready

        val wordAddr: Node[UInt] =
            Node(Node(io.bus.req.bits.addr.asBits).bits(indexWidth + wordOffsetBits - 1, wordOffsetBits)).asUInt
        val wdata: Node[Bits] = Node(io.bus.req.bits.wdata.asBits)
        val wstrobes: Node[Bits] = Node(io.bus.req.bits.wstrb.asBits)
        val held: Node[UInt] = Node(bank.ref(wordAddr))

        // A store replaces the bytes its strobe names and keeps the rest, so a byte store to the window means what it
        // says — the same rule the UART's register file follows. Lane 0 is the word's least significant byte, so the
        // lanes concatenate most-significant first.
        val lanesWritten: Seq[Node[Bits]] = (0 until lanes).map { lane =>
            val hi = (lane + 1) * 8 - 1
            val lo = lane * 8
            val strobed = Node(wstrobes).bits(lane, lane).asUInt === 1.U(1)
            val byte = strobed ? (wdata.bits(hi, lo), held.asBits.bits(hi, lo))
            Node(byte.asBits)
        }
        val merged: Node[Bits] = lanesWritten.reduce((low, high) => Node(high ## low))

        when(fire) {
            busy := true.B
            remain := (parameter.accessCycles - 1).U(countWidth)
            respData := held
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
            when(io.bus.req.bits.wen) {
                for index <- 0 until BankWords do
                    when(wordAddr === index.U(indexWidth)) { bank(index) := merged.asUInt }
            }
        }.otherwise {
            when(busy) {
                when(remain === 0.U(countWidth)) {
                    when(io.bus.resp.ready) { busy := false.B }
                }.otherwise {
                    remain := subWrap(remain, 1.U(countWidth), countWidth)
                }
            }
        }

        // The answer is offered the cycle the count reaches zero — `accessCycles` after the fire — and held until taken.
        io.bus.resp.valid := busy & (remain === 0.U(countWidth))
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId
