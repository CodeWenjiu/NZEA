package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.{DeviceTimings, HasDpiBus}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{DeviceTiming, LiteBusRW}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The platform's UART, in its window at `base`: a 16550-shaped register file and the transmit half of the line.
  *
  * Print-only so far — the direction a program needs, and the one the boot loader does not use. A write to the transmit
  * register hands the byte to the transmitter, which shifts it out at one bit period per `clockHz / lineHz` cycles: the
  * same plain divider [[UartRx]] samples the line with, so the two ends agree without a 16x oversampling clock. The
  * divisor latches are stored for a program that programs them, but the rate is the platform's — the parameter states
  * it, and the hardware does not re-derive it.
  *
  * The receive half is not ported: `LSR.DR` reads 0 and `RBR` reads 0. A program's input arrives through the loader's
  * own receiver ([[nzea_fpga.logic.BootDdr]]), which listens at the pin.
  *
  * Written from the board's legacy fabric UART (tag `legacy-baseline`), whose register semantics and bit timing this
  * keeps.
  */
case class UartParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    clockHz: Int,
    lineHz: Int,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")
    require(clockHz > 0, s"clockHz must be positive, got $clockHz")
    require(lineHz > 0, s"lineHz must be positive, got $lineHz")

    // A bit is a whole number of the fabric's cycles, and four of them keep the bit's own edges inside the period.
    require(
        clockHz / lineHz >= 4,
        s"a bit period of ${clockHz / lineHz} cycles is too short to shift a bit out (need >= 4)"
    )

given upickle.default.ReadWriter[UartParameter] = upickle.default.macroRW

class UartLayers(parameter: UartParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The line the device shows at a level's boundary: the transmit pin, idle high. The receive pin is not declared while
  * the receive half is not ported — the loader's receiver listens at the pin instead.
  */
class UartPins(using TypeImpl, ConstructorApi) extends Bundle:
    val txd = Aligned(Bool())

class UartIO(parameter: UartParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val boundary = Aligned(new UartPins)

class UartProbe(parameter: UartParameter) extends DVBundle[UartParameter, UartLayers](parameter)

@generator
object Uart extends Generator[UartParameter, UartLayers, UartIO, UartProbe] with HasDpiBus:
    override def moduleName(parameter: UartParameter): String = "Uart"

    override def timing: DeviceTiming = DeviceTimings.uart

    def architecture(parameter: UartParameter) =
        val io = summon[Interface[UartIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            parameter.clockHz,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            val bitPeriod = parameter.clockHz / parameter.lineHz
            val periodBits = 32 - Integer.numberOfLeadingZeros(bitPeriod)

            // The register file, byte by byte, at the offsets a program reaches it by: the window is two words —
            // {RBR/THR, IER, IIR/FCR, LCR} and {MCR, LSR, MSR, SCR} — and the write strobe picks the lane, so a
            // whole-word store carries four registers, the way the reference part behaves.
            val local =
                subWrap(io.bus.req.bits.addr, BigInt(parameter.base).U(parameter.addrWidth), parameter.addrWidth)
            val word = Node(local.asBits).bits(3, 2).asUInt
            val wordLo = word === 0.U(2)
            val strobe = io.bus.req.bits.wstrb
            val dataByte0 = Node(io.bus.req.bits.wdata.asBits).bits(7, 0).asUInt
            val dataByte1 = Node(io.bus.req.bits.wdata.asBits).bits(15, 8).asUInt
            val dataByte3 = Node(io.bus.req.bits.wdata.asBits).bits(31, 24).asUInt
            val strobeByte0 = Node(strobe.asBits).bits(0, 0).asUInt === 1.U(1)
            val strobeByte1 = Node(strobe.asBits).bits(1, 1).asUInt === 1.U(1)
            val strobeByte3 = Node(strobe.asBits).bits(3, 3).asUInt === 1.U(1)
            val dlabBit = Node(io.bus.req.bits.wdata.asBits).bits(31, 31).asUInt === 1.U(1)

            val dlab = RegInit(false.B)
            val divisorLow = RegInit(0.U(8))
            val divisorHigh = RegInit(0.U(8))
            val interruptEnable = RegInit(0.U(8))
            val lineControl = RegInit(3.U(8))
            val modemControl = RegInit(0.U(8))
            val transmit = RegInit(0.U(8))
            val pending = RegInit(false.B)

            // The transmitter: `{stop, data, start}` shifted out LSB first, one bit per period.
            val shift = RegInit(0.U(10))
            val bit = RegInit(0.U(4))
            val counter = RegInit(0.U(periodBits))
            val busy = RegInit(false.B)
            val tick = counter === 0.U(periodBits)

            when(!busy) {
                counter := bitPeriod.U(periodBits)
            }.otherwise {
                when(!tick) {
                    counter := prefixSub(counter, 1.U(periodBits), periodBits)._1
                }.otherwise {
                    counter := bitPeriod.U(periodBits)
                }
            }

            // A byte the bus handed over waits in `transmit` until the shift register is free; a second byte may be
            // queued while the first is still shifting, but not a third — the request stall below enforces it.
            when(pending & (!busy)) {
                pending := false.B
            }
            when(busy & tick) {
                when(bit === 9.U(4)) {
                    busy := false.B
                }.otherwise {
                    shift := (0.U(1).asBits ## Node(shift.asBits).bits(9, 1)).asUInt
                }
            }.otherwise {
                when((!busy) & pending) {
                    busy := true.B
                    shift := (1.U(1).asBits ## transmit.asBits ## 0.U(1).asBits).asUInt
                }
            }

            when(!busy) {
                bit := 0.U(4)
            }.otherwise {
                when(busy & tick) {
                    bit := constAdder(bit, 1, 4)
                }
            }

            // The line: idle high, the start bit low, the eight data bits LSB first, the stop bit high.
            val dataBit = Node(shift.asBits).bits(0, 0).asUInt === 1.U(1)
            val runningBit = (bit === 9.U(4)) ? (true.B, dataBit)
            val framedBit = (bit === 0.U(4)) ? (false.B, runningBit)
            val txd = (!busy) ? (true.B, framedBit)
            io.boundary.txd := txd

            // LSR: the transmit side's flags — the register empty (THRE, bit 5) and the transmitter empty (TEMT, bit 6).
            // The receive side's bits read 0 while the receive half is not ported, so nothing reports a byte that
            // cannot arrive.
            val status = ((!pending) & (!busy)).asBits ## (!pending).asBits ## 0.U(6).asBits

            // The read lanes: {LCR, IIR, IER, RBR} and {SCR, MSR, LSR, MCR}. IIR/FCR, MSR and SCR are not modelled —
            // no FIFOs and no modem lines — so their lanes read back as zero.
            val readLow = lineControl.asBits ## 0.U(8).asBits ## interruptEnable.asBits ## 0.U(8).asBits
            val readHigh = 0.U(8).asBits ## 0.U(8).asBits ## status.asBits ## modemControl.asBits
            val readData = wordLo ? (readLow.asUInt, readHigh.asUInt)

            val busCycle = RegInit(0.U(2))
            val respUser = RegInit(0.U(parameter.userWidth))
            val respId = RegInit(0.U(parameter.idWidth))
            val respData = RegInit(0.U(parameter.dataWidth))

            val thrWrite = io.bus.req.bits.wen & wordLo & (!dlab) & strobeByte0
            val reqReady = (busCycle === 0.U(2)) & (!(thrWrite & pending))
            val reqFire = io.bus.req.valid & reqReady

            io.bus.req.ready := reqReady
            io.bus.resp.valid := busCycle === 3.U(2)
            io.bus.resp.bits.data := respData
            io.bus.resp.bits.user := respUser
            io.bus.resp.bits.id := respId

            when(reqFire) {
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                respData := readData
                when(io.bus.req.bits.wen) {
                    when(wordLo) {
                        when(strobeByte0) {
                            // With the divisor latch open the same offset is a divisor byte: stored, never used for
                            // timing (the parameter is what the hardware derives its bit period from).
                            when(dlab) {
                                divisorLow := dataByte0
                            }.otherwise {
                                transmit := dataByte0
                                pending := true.B
                            }
                        }
                        when(strobeByte1) {
                            when(dlab) {
                                divisorHigh := dataByte1
                            }.otherwise {
                                interruptEnable := dataByte1
                            }
                        }
                        when(strobeByte3) {
                            lineControl := dataByte3
                            dlab := dlabBit
                        }
                    }.otherwise {
                        when(strobeByte0) {
                            modemControl := dataByte0
                        }
                    }
                }
                busCycle := 1.U(2)
            }
            when(busCycle === 1.U(2)) { busCycle := 2.U(2) }
            when(busCycle === 2.U(2)) { busCycle := 3.U(2) }
            when(busCycle === 3.U(2) & io.bus.resp.ready) { busCycle := 0.U(2) }

        // The hardware-only output: the line idles high when the simulator answers the bus instead.
        if parameter.flow.bridges then io.boundary.txd := true.B
