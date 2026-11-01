package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.{DeviceTimings, HasDpiBus}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The fabric's RAM slave: a 128 KiB memory behind the bus, `dataWidth / 8` bytes per word.
  *
  * The memory runs on its **own clock** ([[RamPins.clock]], an input at the level's boundary): a memory macro's cycle
  * time is a property of the macro, not of the logic around it — this one needs 3.03 ns — so clocking it from the tile
  * would cap the tile at the macro's frequency. The two domains talk over a two-phase toggle handshake, and the request
  * payload is held stable from the fire until the acknowledge returns, which is what makes the multi-bit crossing safe.
  * In the bridge flow the memory is the simulator's, so [[HasDpiBus.answerBus]] answers the bus instead.
  *
  * One word-wide synchronous memory carries the byte strobe as its write mask, and its port shape is the parameter's
  * ([[SyncRamPorts]]): a 32-bit core gets a 32-bit memory, a 64-bit one 64, so the word count shrinks as the word grows
  * and the window stays 128 KiB.
  */
case class RamFabricSlaveParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    clockHz: Int,
    base: Long,
    size: Long,
    ports: SyncRamPorts,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(clockHz >= 1, s"clockHz must be >= 1, got $clockHz")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")
    require(size > 0, s"size must be > 0, got $size")

    val depth: Int =
        val words = size / (dataWidth / 8)
        require(words > 0 && (words & (words - 1)) == 0, s"size must be a power-of-two number of words, got $size")
        words.toInt

given upickle.default.ReadWriter[RamFabricSlaveParameter] = upickle.default.macroRW

class RamFabricSlaveLayers(parameter: RamFabricSlaveParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The pins the RAM shows at a level's boundary: the clock its memory runs on.
  *
  * An input, which is why the boundary connect is the bidirectional `:<>=`: the direction of a boundary field is the
  * bundle's own, not "everything the device drives".
  */
class RamPins(using TypeImpl, ConstructorApi) extends Bundle:
    val clock = Flipped(Clock())

class RamFabricSlaveIO(parameter: RamFabricSlaveParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val boundary = Aligned(new RamPins)

class RamFabricSlaveProbe(parameter: RamFabricSlaveParameter)
    extends DVBundle[RamFabricSlaveParameter, RamFabricSlaveLayers](parameter)

@generator
object RamFabricSlave
    extends Generator[RamFabricSlaveParameter, RamFabricSlaveLayers, RamFabricSlaveIO, RamFabricSlaveProbe]
    with HasDpiBus:
    override def moduleName(parameter: RamFabricSlaveParameter): String = "RamFabricSlave"

    def architecture(parameter: RamFabricSlaveParameter) =
        val io = summon[Interface[RamFabricSlaveIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            Some(DeviceTimings.ram),
            parameter.clockHz,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            // The window is byte-addressed: the word index drops the word's byte offset from the byte index.
            val wordBytes = parameter.dataWidth / 8
            val wordOffsetBits = log2Ceil(wordBytes)
            val wordAddrBits = log2Ceil(parameter.depth)

            val mem = SyncRam.instantiate(
                SyncRamParameter(parameter.dataWidth, parameter.depth, parameter.ports, SyncRamReadUnderWrite.ReadFirst)
            )

            val busCycle = RegInit(0.U(2))
            val isRead = RegInit(false.B)
            val respUser = RegInit(0.U(parameter.userWidth))
            val respId = RegInit(0.U(parameter.idWidth))
            val respData = RegInit(0.U(parameter.dataWidth))

            val reqReady = busCycle === 0.U(2)
            val reqFire = io.bus.req.valid & reqReady

            io.bus.req.ready := reqReady
            io.bus.resp.valid := busCycle === 3.U(2)
            io.bus.resp.bits.data := isRead ? (respData, 0.U(parameter.dataWidth))
            io.bus.resp.bits.user := respUser
            io.bus.resp.bits.id := respId

            val localByteAddr =
                subWrap(io.bus.req.bits.addr, BigInt(parameter.base).U(parameter.addrWidth), parameter.addrWidth)
            val wordAddr = Node(localByteAddr.asBits).bits(wordAddrBits + wordOffsetBits - 1, wordOffsetBits).asUInt

            // The held request: stable from the fire until the acknowledge returns, so the memory side may sample it.
            val reqAddr = RegInit(0.U(wordAddrBits))
            val reqWdata = RegInit(0.U(parameter.dataWidth))
            val reqWmask = RegInit(0.U(wordBytes))
            val reqWen = RegInit(false.B)
            val reqToggle = RegInit(false.B)

            // The acknowledge, synchronized into the tile's domain.
            val ackSync0 = RegInit(false.B)
            val ackSync1 = RegInit(false.B)

            val (memAck, memRdata) = ClockScope.posedge(io.boundary.clock):
                val reqSync0 = RegInit(false.B)
                val reqSync1 = RegInit(false.B)
                reqSync0 := reqToggle
                reqSync1 := reqSync0

                val memAck = RegInit(false.B)
                val memRdata = RegInit(0.U(parameter.dataWidth))
                val memState = RegInit(0.U(2))
                val memAddr = RegInit(0.U(wordAddrBits))
                val memWen = RegInit(false.B)
                val memWdata = RegInit(0.U(parameter.dataWidth))
                val memWmask = RegInit(0.U(wordBytes))

                // The payload is latched in this domain (it has been stable since the toggle), the macro samples it the
                // next cycle, and its output is captured the cycle after that — one macro access, no more.
                when((reqSync1 =/= memAck) & (memState === 0.U(2))) {
                    memAddr := reqAddr
                    memWen := reqWen
                    memWdata := reqWdata
                    memWmask := reqWmask
                    memState := 1.U(2)
                }
                when(memState === 1.U(2)) { memState := 2.U(2) }
                when(memState === 2.U(2)) {
                    memRdata := mem.io.rData
                    memAck := !memAck
                    memState := 0.U(2)
                }

                mem.io.addr := memAddr
                mem.io.wEn := memWen & (memState === 1.U(2))
                mem.io.wData := memWdata
                mem.io.wMask := memWmask
                (memAck, memRdata)

            mem.io.clock := io.boundary.clock
            ackSync0 := memAck
            ackSync1 := ackSync0

            when(reqFire) {
                reqAddr := wordAddr
                reqWdata := io.bus.req.bits.wdata
                reqWmask := io.bus.req.bits.wstrb
                reqWen := io.bus.req.bits.wen
                reqToggle := !reqToggle
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                isRead := !io.bus.req.bits.wen
                busCycle := 1.U(2)
            }
            // The acknowledge mirrors the toggle this request flipped, so the copies have caught up when they *match* it:
            // the memory side has completed the access by then and the data register holds this access's result.
            when(busCycle === 1.U(2) & (ackSync1 === reqToggle)) {
                respData := memRdata
                busCycle := 2.U(2)
            }
            when(busCycle === 2.U(2)) { busCycle := 3.U(2) }
            when(busCycle === 3.U(2) & io.bus.resp.ready) { busCycle := 0.U(2) }
