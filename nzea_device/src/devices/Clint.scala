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

/** The RISC-V CLINT: an `mtime` counter and `mtimecmp`, with the timer interrupt.
  *
  * The memory map, relative to `base`: `0x0000` msip, `0x4000`/`0x4004` `mtimecmp` low/high, `0xBFF8`/`0xBFFC` `mtime`
  * low/high. `mtime` free-runs; a write sets `mtimecmp`, a read selects a register. The request's `user`/`id` are
  * captured at acceptance because the master has moved on when the answer is due. In the bridge flow the timer is the
  * simulator's, so [[HasDpiBus.answerBus]] answers the bus and the interrupt is inert.
  */
case class ClintParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, base: Long, flow: RtlFlow)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 32, s"dataWidth must be at least 32, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(base >= 0, s"base must be >= 0, got $base")

given upickle.default.ReadWriter[ClintParameter] = upickle.default.macroRW

class ClintLayers(parameter: ClintParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class ClintPins(using TypeImpl, ConstructorApi) extends Bundle:
    val timer_interrupt = Aligned(Bool())

class ClintIO(parameter: ClintParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val bus = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val boundary = Aligned(new ClintPins)

class ClintProbe(parameter: ClintParameter) extends DVBundle[ClintParameter, ClintLayers](parameter)

@generator
object Clint extends Generator[ClintParameter, ClintLayers, ClintIO, ClintProbe] with HasDpiBus:
    override def moduleName(parameter: ClintParameter): String = "Clint"

    override def timing: DeviceTiming = DeviceTimings.clint

    def architecture(parameter: ClintParameter) =
        val io = summon[Interface[ClintIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        answerBus(
            parameter.flow,
            io.bus,
            io.resetN.asBool,
            parameter.addrWidth,
            parameter.dataWidth,
            parameter.userWidth,
            parameter.idWidth
        ):
            val dw = parameter.dataWidth
            val mtime = RegInit(0.U(64))
            // The increment is the shaped constant adder, not `+ 1`: the free-running counter's carry is the whole
            // 64-bit width, and a mapper left to itself decomposes it into a ripple — bit 0's carry reaches bit 63
            // through sixteen levels. The prefix form's depth is the width's logarithm.
            mtime := constAdder(mtime, 1, 64)
            val mtimecmp = RegInit(0.U(64))

            val busy = RegInit(false.B)
            val respUser = RegInit(0.U(parameter.userWidth))
            val respId = RegInit(0.U(parameter.idWidth))
            val respData = RegInit(0.U(dw))

            io.bus.req.ready := !busy
            io.bus.resp.valid := busy
            io.bus.resp.bits.data := respData
            io.bus.resp.bits.user := respUser
            io.bus.resp.bits.id := respId

            val localAddr = subWrap(
                io.bus.req.bits.addr,
                BigInt(parameter.base).U(parameter.addrWidth),
                parameter.addrWidth
            )
            val reqFire = io.bus.req.valid & io.bus.req.ready

            // The values a guard writes are named outside it, so every operand lives in the module body and no node is
            // created inside a region that a sibling region would have to reference. The registers are 32-bit; a wider
            // bus access touches their low word, and a read zero-extends them to the bus width.
            val cmpHi = Node(mtimecmp.asBits).bits(63, 32)
            val cmpLo = Node(mtimecmp.asBits).bits(31, 0)
            val mtimeHi = Node(mtime.asBits).bits(63, 32)
            val mtimeLo = Node(mtime.asBits).bits(31, 0)
            val wdataLo = Node(io.bus.req.bits.wdata.asBits).bits(31, 0)
            val cmpWriteHi = (cmpHi ## wdataLo).asUInt
            val cmpWriteLo = (wdataLo ## cmpLo).asUInt
            val readData = muxLookup(localAddr, 0.U(dw))(
                Seq(
                    BigInt(0x0000).U(parameter.addrWidth) -> 0.U(dw),
                    BigInt(0x4000).U(parameter.addrWidth) -> cmpLo.asBits.pad(dw).asUInt,
                    BigInt(0x4004).U(parameter.addrWidth) -> cmpHi.asBits.pad(dw).asUInt,
                    BigInt(0xbff8).U(parameter.addrWidth) -> mtimeLo.asBits.pad(dw).asUInt,
                    BigInt(0xbffc).U(parameter.addrWidth) -> mtimeHi.asBits.pad(dw).asUInt
                )
            )

            when(reqFire) {
                respUser := io.bus.req.bits.user
                respId := io.bus.req.bits.id
                busy := true.B
                when(io.bus.req.bits.wen) {
                    when(localAddr === BigInt(0x4000).U(parameter.addrWidth)) { mtimecmp := cmpWriteHi }
                    when(localAddr === BigInt(0x4004).U(parameter.addrWidth)) { mtimecmp := cmpWriteLo }
                }.otherwise {
                    respData := readData
                }
            }

            when(io.bus.resp.valid & io.bus.resp.ready) { busy := false.B }

            io.boundary.timer_interrupt := (mtime >= mtimecmp) & (mtimecmp =/= 0.U(64))

        // The hardware-only output: inert when the simulator answers the bus instead.
        if parameter.flow.bridges then io.boundary.timer_interrupt := false.B
