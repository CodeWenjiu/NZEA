package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{BusRegions, LiteBusRW}
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The wire's facts: the bus clock's rate, the bit rate, and the 7-bit address every frame on this wire starts with. */
case class I2cMasterParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    clockHz: Int,
    bitHz: Int,
    slaveAddr: Int,
    counters: Boolean,
    stats: StatScope,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 10, s"addrWidth must be >= 10 (a frame carries an 8-bit word pointer), got $addrWidth")
    require(dataWidth == 32, s"a frame carries four data bytes, so dataWidth must be 32, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")
    require(clockHz > 0, s"clockHz must be positive, got $clockHz")
    require(bitHz > 0, s"bitHz must be positive, got $bitHz")
    require(clockHz / bitHz >= 4, s"a bit of ${clockHz / bitHz} cycles cannot carry both phases (need >= 4)")
    require(slaveAddr >= 0 && slaveAddr < 128, s"slaveAddr must be a 7-bit address, got $slaveAddr")

given upickle.default.ReadWriter[I2cMasterParameter] = upickle.default.macroRW

class I2cMasterLayers(parameter: I2cMasterParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The two open-drain line levels the master drives, high meaning released. */
class I2cPins(using TypeImpl, ConstructorApi) extends Bundle:
    val scl = Aligned(Bool())
    val sda = Aligned(Bool())

class I2cMasterIO(parameter: I2cMasterParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val boundary = Aligned(new I2cPins)

class I2cMasterProbe(parameter: I2cMasterParameter) extends DVBundle[I2cMasterParameter, I2cMasterLayers](parameter)

/** One register window on an I2C wire: a 32-bit access becomes the byte sequence an I2C register access is, and the
  * window answers when the last bit has left the wire.
  *
  * A write frame is START, address+W, the word pointer, four data bytes and STOP — 56 bits; a read frame adds the
  * repeated START and the address+R phase — 66 bits. Each bit is one `clockHz / bitHz` period, SDA changes while SCL is
  * low, and START/STOP are the two exceptions that change it while SCL is high. The pointer is the access's word index
  * inside the window, which is what an 8-bit register pointer can carry for these maps.
  *
  * The far end is the flow's: a synthesis flow's wire ends at the pins, while the bridge flow's ends at the simulator —
  * the word is read or written through the bus DPI at the turnaround, and the pins are still driven so the frame can be
  * watched. The slave address is declared, not discovered: there is one device behind each window.
  */
@generator
object I2cMaster extends Generator[I2cMasterParameter, I2cMasterLayers, I2cMasterIO, I2cMasterProbe]:

    /** The body states the wire: its base (the pointer byte), its slave address, its rate, and the counter scope — so
      * one name is one body, and three windows on three wires cannot collapse into one module.
      */
    override def moduleName(parameter: I2cMasterParameter): String =
        val scope = if parameter.stats.path.isEmpty then "root" else parameter.stats.path.mkString("_")
        s"I2cMaster_${scope}_a${parameter.slaveAddr}_b${parameter.bitHz}_c${parameter.clockHz}_${parameter.base}"

    private val StartField = 0
    private val AddrField = 1
    private val PointerField = 2
    private val RestartField = 3
    private val AddrReadField = 4
    private val DataField = 5
    private val StopField = 6

    def architecture(parameter: I2cMasterParameter) =
        val io = summon[Interface[I2cMasterIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val bitPeriod = parameter.clockHz / parameter.bitHz
        val periodBits = log2Ceil(bitPeriod)
        val reload = (bitPeriod - 1).U(periodBits)
        val halfPeriod = (bitPeriod / 2).U(periodBits)

        val busy = RegInit(false.B)
        val isRead = RegInit(false.B)
        val addrReg = RegInit(0.U(parameter.addrWidth))
        val wdataReg = RegInit(0.U(parameter.dataWidth))
        val wstrbReg = RegInit(0.U(parameter.dataWidth / 8))
        val field = RegInit(0.U(3))
        val bitInField = RegInit(0.U(4))
        val byteInData = RegInit(0.U(2))
        val counter = RegInit(reload)
        val txByte = RegInit(0.U(8))
        val txWord = RegInit(0.U(parameter.dataWidth))
        val readWord = RegInit(0.U(parameter.dataWidth))
        val respValid = RegInit(false.B)
        val respData = RegInit(0.U(parameter.dataWidth))
        val respUser = RegInit(0.U(parameter.userWidth))
        val respId = RegInit(0.U(parameter.idWidth))

        val tick = counter === 0.U(periodBits)
        val lastBitOfField = bitInField === 8.U(4)
        val lastDataByte = byteInData === 3.U(2)

        when(!busy) {
            counter := reload
        }.otherwise {
            when(tick) { counter := reload }.otherwise {
                counter := prefixSub(counter, 1.U(periodBits), periodBits)._1
            }
        }

        val local = subWrap(addrReg, BigInt(parameter.base).U(parameter.addrWidth), parameter.addrWidth)
        val pointerByte = Node(local.asBits).bits(9, 2).asUInt
        val addrWrite = (parameter.slaveAddr << 1).U(8)
        val addrRead = ((parameter.slaveAddr << 1) | 1).U(8)

        val readEnable = busy & (field === AddrReadField.U(3)) & lastBitOfField
        val farRead: Option[Wire[UInt]] =
            if parameter.flow.bridges then
                Some(
                    dpiUnclockedCall("bus_read", UInt(parameter.dataWidth), Some(Seq("addr")), Some("rdata"))(
                        readEnable,
                        addrReg
                    )
                )
            else None

        when(busy & tick & (bitInField < 8.U(4))) {
            when(
                (field === AddrField.U(3)) | (field === PointerField.U(3)) | (field === AddrReadField.U(3))
            ) {
                txByte := Node((txByte << 1).asBits).bits(7, 0).asUInt
            }.otherwise {
                when(field === DataField.U(3)) {
                    txWord := Node((txWord << 1).asBits).bits(parameter.dataWidth - 1, 0).asUInt
                }
            }
        }

        when(busy & tick) {
            when(field === StartField.U(3)) {
                field := AddrField.U(3)
                bitInField := 0.U(4)
                txByte := addrWrite
            }.otherwise {
                when(field === AddrField.U(3)) {
                    when(lastBitOfField) {
                        field := PointerField.U(3)
                        bitInField := 0.U(4)
                        txByte := pointerByte
                    }.otherwise { bitInField := constAdder(bitInField, 1, 4) }
                }.otherwise {
                    when(field === PointerField.U(3)) {
                        when(lastBitOfField) {
                            bitInField := 0.U(4)
                            when(isRead) {
                                field := RestartField.U(3)
                            }.otherwise {
                                field := DataField.U(3)
                                byteInData := 0.U(2)
                                txWord := wdataReg
                            }
                        }.otherwise { bitInField := constAdder(bitInField, 1, 4) }
                    }.otherwise {
                        when(field === RestartField.U(3)) {
                            field := AddrReadField.U(3)
                            bitInField := 0.U(4)
                            txByte := addrRead
                        }.otherwise {
                            when(field === AddrReadField.U(3)) {
                                when(lastBitOfField) {
                                    field := DataField.U(3)
                                    bitInField := 0.U(4)
                                    byteInData := 0.U(2)
                                    farRead match
                                        case Some(word) =>
                                            txWord := word
                                            readWord := word
                                        case None =>
                                            txWord := 0.U(parameter.dataWidth)
                                            readWord := 0.U(parameter.dataWidth)
                                }.otherwise { bitInField := constAdder(bitInField, 1, 4) }
                            }.otherwise {
                                when(field === DataField.U(3) & lastBitOfField) {
                                    bitInField := 0.U(4)
                                    when(lastDataByte) {
                                        field := StopField.U(3)
                                    }.otherwise {
                                        byteInData := constAdder(byteInData, 1, 2)
                                    }
                                }.otherwise {
                                    when(field === DataField.U(3)) {
                                        bitInField := constAdder(bitInField, 1, 4)
                                    }.otherwise {
                                        busy := false.B
                                        respValid := true.B
                                        respData := isRead ? (readWord, 0.U(parameter.dataWidth))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        when(respValid & io.bus.resp.ready) { respValid := false.B }

        val frameEnd =
            busy & (field === DataField.U(3)) & lastBitOfField & lastDataByte & tick
        if parameter.flow.bridges then
            dpiClockedCall("bus_write", Some(Seq("addr", "wdata", "wstrb")))(
                io.clock,
                frameEnd & !isRead,
                addrReg,
                wdataReg,
                wstrbReg.asBits.pad(32)
            )

        val reqReady = (!busy) & ((!respValid) | io.bus.resp.ready) & io.resetN.asBool
        io.bus.req.ready := reqReady
        val fire = io.bus.req.valid & reqReady
        when(fire) {
            addrReg := io.bus.req.bits.addr
            wdataReg := io.bus.req.bits.wdata
            wstrbReg := io.bus.req.bits.wstrb
            respUser := io.bus.req.bits.user
            respId := io.bus.req.bits.id
            isRead := !io.bus.req.bits.wen
            busy := true.B
            field := StartField.U(3)
            bitInField := 0.U(4)
            byteInData := 0.U(2)
        }

        io.bus.resp.valid := respValid
        io.bus.resp.bits.data := respData
        io.bus.resp.bits.user := respUser
        io.bus.resp.bits.id := respId

        val sclHigh = counter < halfPeriod
        val ackLevel: Referable[Bool] =
            isRead ? (lastDataByte ? (true.B, false.B), true.B)
        val byteLevel = lastBitOfField ? (true.B, Node(txByte.asBits).bits(7, 7).asUInt === 1.U(1))
        val wordLevel = lastBitOfField ? (ackLevel, Node(txWord.asBits).bits(31, 31).asUInt === 1.U(1))
        val bitLevel: Referable[Bool] =
            (field === DataField.U(3)) ? (wordLevel, byteLevel)
        val startOrRestart =
            (field === StartField.U(3)) | (field === RestartField.U(3))
        val stop = field === StopField.U(3)
        val special = startOrRestart | stop
        val specialLevel = stop ? (sclHigh ? (true.B, false.B), (sclHigh ? (false.B, true.B)))

        io.boundary.scl := (!busy) | special | sclHigh
        io.boundary.sda := (!busy) ? (true.B, (special ? (specialLevel, bitLevel)))

        if parameter.counters then
            val frames = parameter.stats.stat("frames", BusRegions.Bus, "frames started (one access each)")
            frames.inc(fire)
            val busyCycles = parameter.stats.stat("busy", BusRegions.Bus, "cycles the wire is held by a frame")
            busyCycles.inc(busy)
