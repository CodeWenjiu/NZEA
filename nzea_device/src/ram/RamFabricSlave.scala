package nzea_device.ram

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
  * One byte-wide synchronous memory per byte lane carries the byte strobe, and a write-first read policy lets a read on
  * the write's own edge see the incoming data — the same shape a memory macro is inferred from. The width is the bus's
  * (a 32-bit core gets four lanes, a 64-bit one eight), so the word count shrinks as the word grows and the window
  * stays 128 KiB. A bus access takes a fixed four-cycle residency. In the bridge flow the memory is the simulator's, so
  * [[HasDpiBus.answerBus]] answers the bus instead and the hardware below is not built.
  */
case class RamFabricSlaveParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    base: Long,
    flow: RtlFlow
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")

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
            // The window is 128 KiB and byte-addressed, so the word index drops the word's byte offset from the byte
            // index and the word count is the rest — four lanes leave 32 Ki words, eight leave 16 Ki.
            val wordBytes = parameter.dataWidth / 8
            val wordOffsetBits = log2Ceil(wordBytes)
            val depth = 1 << (17 - wordOffsetBits)

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
            val wordAddr = Node(localByteAddr.asBits).bits(16, wordOffsetBits).asUInt
            val wenClean = reqFire & io.bus.req.bits.wen
            val renClean = reqFire & !io.bus.req.bits.wen

            val wrAddr = wordAddr
            val wrEn = wenClean

            val bytes = (0 until wordBytes).map { i =>
                val mem = SyncRam.instantiate(
                    SyncRamParameter(8, depth, SyncRamPorts.OneR1W, SyncRamReadUnderWrite.WriteFirst)
                )
                mem.io.clock := io.clock
                mem.io.rEn := renClean
                mem.io.rAddr := wordAddr
                mem.io.wAddr := wrAddr
                val reqByte = Node(io.bus.req.bits.wdata.asBits).bits(8 * i + 7, 8 * i).asUInt
                mem.io.wData := reqByte
                mem.io.wEn := wrEn & Node(io.bus.req.bits.wstrb.asBits).bit(i)
                mem.io.rData
            }

            // Little-endian: lane 0 is the least significant byte, so the concatenation runs high lane to low.
            readData := bytes.reverse.map(b => Node(b.asBits)).reduceLeft((hi, lo) => hi ## lo).asUInt

            when(reqFire) {
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                isRead := !io.bus.req.bits.wen
                busCycle := 1.U(2)
            }
            when(busCycle === 1.U(2)) { busCycle := 2.U(2) }
            when(busCycle === 2.U(2)) { busCycle := 3.U(2) }
            when(busCycle === 3.U(2) & io.bus.resp.ready) { busCycle := 0.U(2) }
