package nzea_core.front.ifu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.{*, given}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.Decoupled
import nzea_config.core.CoreConfig
import nzea_rtl.LiteBusRO
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One fetch result: the pc the word was read from, and the word itself. */
class FetchedInst(width: Int) extends Bundle:
  val pc = Aligned(UInt(width))
  val inst = Aligned(UInt(32))

case class IfuParameter(isa: String, defaultPc: Long) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[IfuParameter] = upickle.default.macroRW

class IfuLayers(parameter: IfuParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IfuIO(parameter: IfuParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val ibus = Aligned(Ifu.busType(parameter.config))
  val redirect = Flipped(Valid(UInt(parameter.config.width)))
  val out = Aligned(Decoupled(new FetchedInst(parameter.config.width)))

class IfuProbe(parameter: IfuParameter) extends DVBundle[IfuParameter, IfuLayers](parameter)

/** In-order fetcher: `pc`, `pc+4`, one request at a time, no prediction (always-not-taken).
  *
  * Redirect is the only event in this module's world: it steers pc and starts a new epoch, which is also how the fetch
  * stream is cancelled — a response of a killed stream is consumed and digested, never refused. The fetcher never gates
  * on reset: it asks and lets the slave refuse (a slave that is not ready does not accept), so the pc only moves on a
  * real transfer.
  */
@generator
object Ifu extends Generator[IfuParameter, IfuLayers, IfuIO, IfuProbe]:
  override def moduleName(parameter: IfuParameter): String = "Ifu"

  /** Fetch bus shape: address and data are the ISA word width. Every request is id 0 — a fetch stream is one class of
    * traffic, and cancellation travels in the user field rather than in the id.
    */
  val busIdWidth: Int = 1

  def busType(config: CoreConfig) =
    new LiteBusRO(config.width, config.width, IbusUser.userWidth(config.width), busIdWidth)

  def architecture(parameter: IfuParameter) =
    val io = summon[Interface[IfuIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val width = parameter.config.width
    // The platform's reset pc, masked to the register width: a BigInt shift, because `1L << 64` wraps around in a Long.
    val pcReset = BigInt(parameter.config.defaultPc) & ((BigInt(1) << width) - 1)
    val pc = RegInit(pcReset.U(width))
    val epoch = RegInit(0.U(IbusUser.epochBits))

    // A prefix adder, not `+`: abc maps a general add to a carry chain, and this incrementer sits on the fetch-rate path
    // (`pc` to `pc` in one cycle), so its depth is the front end's stage length.
    val pcNext = prefixAdder(pc, 4.U(width), false.B, width)
    val epochNext = (epoch.asBits.asUInt + 1.U(IbusUser.epochBits)).asBits.bits(IbusUser.epochBits - 1, 0).asUInt

    when(io.redirect.valid) {
      pc := io.redirect.bits
      epoch := epochNext
    }.otherwise {
      when(io.ibus.req.fire) {
        pc := pcNext
      }
    }

    io.ibus.req.valid := io.out.ready
    io.ibus.req.bits.addr := pc
    io.ibus.req.bits.user := IbusUser.pack(pc, epoch)
    io.ibus.req.bits.id := 0.U(Ifu.busIdWidth)

    // `out.ready` is the only condition on accepting a response: a fetch from a killed stream is consumed and digested
    // (out.valid stays low) instead of being refused, so no request is left dangling on the bus.
    io.ibus.resp.ready := io.out.ready
    io.out.valid := io.ibus.resp.valid & (IbusUser.epochOf(io.ibus.resp.bits.user, width) === epoch)
    io.out.bits.pc := IbusUser.pcOf(io.ibus.resp.bits.user, width)
    // The bus answers in machine words — the DPI boundary is XLEN wide all the way across — while an instruction word is
    // 32 bits in every base ISA this core has, so the fetch reads the low word out of the answer.
    io.out.bits.inst := Node(io.ibus.resp.bits.data.asBits).bits(31, 0).asUInt
