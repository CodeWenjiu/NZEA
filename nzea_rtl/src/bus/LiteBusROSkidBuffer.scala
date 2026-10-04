package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A two-entry skid buffer on a read-only bus's request channel.
  *
  * A fetch's request valid follows its own pipeline's ready and its pc advances on the request's acceptance, so a
  * master whose port is wired live into a fabric lets the fabric's arbitration reach back into the pipeline —
  * `req.ready` into `req.fire`, and on into the predictor's read address. A one-deep elastic register still couples the
  * two: its ready is `!valid | out.ready`, a downstream signal. The skid cuts it: with two entries ready is `!full`,
  * the buffer's own state, so nothing downstream reaches the master's fire, while one request per cycle still goes
  * through at steady state. The response channel passes through combinationally.
  */
case class LiteBusROSkidBufferParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[LiteBusROSkidBufferParameter] = upickle.default.macroRW

class LiteBusROSkidBufferLayers(parameter: LiteBusROSkidBufferParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusROSkidBufferIO(parameter: LiteBusROSkidBufferParameter) extends HWBundle(parameter):
    private val bus = new LiteBusRO(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The upstream (master) side: the buffer is its slave. */
    val in = Flipped(bus)

    /** The downstream (fabric) side: the buffer is its master. */
    val out = Aligned(bus)

class LiteBusROSkidBufferProbe(parameter: LiteBusROSkidBufferParameter)
    extends DVBundle[LiteBusROSkidBufferParameter, LiteBusROSkidBufferLayers](parameter)

@generator
object LiteBusROSkidBuffer
    extends Generator[
        LiteBusROSkidBufferParameter,
        LiteBusROSkidBufferLayers,
        LiteBusROSkidBufferIO,
        LiteBusROSkidBufferProbe
    ]:
    override def moduleName(parameter: LiteBusROSkidBufferParameter): String = "LiteBusROSkidBuffer"

    def architecture(parameter: LiteBusROSkidBufferParameter) =
        val io = summon[Interface[LiteBusROSkidBufferIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        // Two entries: the head (`a`) is what the fabric sees, the tail (`b`) is the skid. Ready is `!full` — the
        // buffer's own state — so the master's fire never waits on the fabric.
        val aValid = RegInit(false.B)
        val aBits = Reg(new LiteReqRO(parameter.addrWidth, parameter.userWidth, parameter.idWidth))
        val bValid = RegInit(false.B)
        val bBits = Reg(new LiteReqRO(parameter.addrWidth, parameter.userWidth, parameter.idWidth))

        io.in.req.ready := !(aValid & bValid)
        io.out.req.valid := aValid
        io.out.req.bits.addr := aBits.addr
        io.out.req.bits.user := aBits.user
        io.out.req.bits.id := aBits.id

        // The four cases keep one request per cycle when the fabric drains every cycle: a push with the head empty
        // lands in the head (whether or not the head is being taken), and a push with the head occupied lands in the
        // tail; a pop shifts the tail into the head.
        val inFire = io.in.req.valid & io.in.req.ready
        val outFire = io.out.req.valid & io.out.req.ready
        when(outFire) {
            when(bValid) {
                aValid := true.B
                aBits :<= bBits
                when(inFire) {
                    bValid := true.B
                    bBits :<= io.in.req.bits
                }.otherwise {
                    bValid := false.B
                }
            }.otherwise {
                when(inFire) {
                    aValid := true.B
                    aBits :<= io.in.req.bits
                }.otherwise {
                    aValid := false.B
                }
                bValid := false.B
            }
        }.otherwise {
            when(inFire) {
                when(aValid) {
                    bValid := true.B
                    bBits :<= io.in.req.bits
                }.otherwise {
                    aValid := true.B
                    aBits :<= io.in.req.bits
                }
            }
        }

        // Response: straight through; the fabric drives valid/bits and the master drives ready.
        io.in.resp.valid := io.out.resp.valid
        io.in.resp.bits :<>= io.out.resp.bits
        io.out.resp.ready := io.in.resp.ready
