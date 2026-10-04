package nzea_device.devices

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_device.HasDpiBus
import nzea_rtl.RtlFlow
import nzea_rtl.bus.LiteBusRW
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The fabric's RAM slave: a 128 KiB memory behind the bus, `dataWidth / 8` bytes per word.
  *
  * One word-wide synchronous memory carries the byte strobe as its write mask, and its port shape is the parameter's
  * ([[SyncRamPorts]]) — a target whose macro is single-port builds one, and a target with a wider macro says so without
  * touching this device. The width is the bus's (a 32-bit core gets a 32-bit memory, a 64-bit one 64), so the word
  * count shrinks as the word grows and the window stays 128 KiB. A bus access takes a fixed four-cycle residency. In
  * the bridge flow the memory is the simulator's, so [[HasDpiBus.answerBus]] answers the bus instead and the hardware
  * below is not built.
  */
case class RamFabricSlaveParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    size: Long,
    ports: SyncRamPorts,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")
    require(size > 0, s"size must be > 0, got $size")

    /** The memory's word count: the window's size in words, a power of two. */
    val depth: Int =
        val words = size / (dataWidth / 8)
        require(words > 0 && (words & (words - 1)) == 0, s"size must be a power-of-two number of words, got $size")
        words.toInt

given upickle.default.ReadWriter[RamFabricSlaveParameter] = upickle.default.macroRW

class RamFabricSlaveLayers(parameter: RamFabricSlaveParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class RamFabricSlaveIO(parameter: RamFabricSlaveParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

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
            io.bus,
            io.resetN.asBool,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            // The window is byte-addressed; the word index drops the word's byte offset from the byte index, and the
            // word count is the window's size in words.
            val wordBytes = parameter.dataWidth / 8
            val wordOffsetBits = log2Ceil(wordBytes)
            val wordAddrBits = log2Ceil(parameter.depth)

            val busCycle = RegInit(0.U(2))
            val isRead = RegInit(false.B)
            val respUser = RegInit(0.U(parameter.userWidth))
            val respId = RegInit(0.U(parameter.idWidth))
            val readData = RegInit(0.U(parameter.dataWidth))

            val reqReady = busCycle === 0.U(2)
            val reqFire = io.bus.req.valid & reqReady

            io.bus.req.ready := reqReady
            io.bus.resp.valid := busCycle === 3.U(2)
            io.bus.resp.bits.data := isRead ? (readData, 0.U(parameter.dataWidth))
            io.bus.resp.bits.user := respUser
            io.bus.resp.bits.id := respId

            val localByteAddr =
                subWrap(io.bus.req.bits.addr, BigInt(parameter.base).U(parameter.addrWidth), parameter.addrWidth)
            val wordAddr = Node(localByteAddr.asBits).bits(wordAddrBits + wordOffsetBits - 1, wordOffsetBits).asUInt
            val wenClean = reqFire & io.bus.req.bits.wen

            val mem = SyncRam.instantiate(
                SyncRamParameter(
                    parameter.dataWidth,
                    parameter.depth,
                    parameter.ports,
                    SyncRamReadUnderWrite.ReadFirst
                )
            )
            mem.io.clock := io.clock
            mem.io.addr := wordAddr
            mem.io.wEn := wenClean
            mem.io.wData := io.bus.req.bits.wdata
            mem.io.wMask := io.bus.req.bits.wstrb
            readData := mem.io.rData

            when(reqFire) {
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                isRead := !io.bus.req.bits.wen
                busCycle := 1.U(2)
            }
            when(busCycle === 1.U(2)) { busCycle := 2.U(2) }
            when(busCycle === 2.U(2)) { busCycle := 3.U(2) }
            when(busCycle === 3.U(2) & io.bus.resp.ready) { busCycle := 0.U(2) }
