package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A one-stage elastic register on the request channel.
  *
  * A fabric's arbiter grants a slave to one of the masters that want it, so a master's `req.ready` depends on every
  * other master's `req.valid` — a cross-master combinational path. When a master's own request valid is in turn derived
  * from a downstream ready (an in-order core fetches only when its pipeline can advance), that closes a loop through
  * the masters. This slice breaks it: the request is registered, so what the fabric's arbitration sees is state, not
  * the masters' current valids, while the elastic handshake keeps one request per cycle at steady state.
  *
  * The response channel passes through combinationally: a fabric's response is register-driven at its own ports, so it
  * couples nothing back.
  */
case class LiteBusRegisterSliceParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[LiteBusRegisterSliceParameter] = upickle.default.macroRW

class LiteBusRegisterSliceLayers(parameter: LiteBusRegisterSliceParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusRegisterSliceIO(parameter: LiteBusRegisterSliceParameter) extends HWBundle(parameter):
    private val bus = new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The upstream (master) side: the slice is its slave. */
    val in = Flipped(bus)

    /** The downstream (fabric) side: the slice is its master. */
    val out = Aligned(bus)

class LiteBusRegisterSliceProbe(parameter: LiteBusRegisterSliceParameter)
    extends DVBundle[LiteBusRegisterSliceParameter, LiteBusRegisterSliceLayers](parameter)

@generator
object LiteBusRegisterSlice
    extends Generator[
        LiteBusRegisterSliceParameter,
        LiteBusRegisterSliceLayers,
        LiteBusRegisterSliceIO,
        LiteBusRegisterSliceProbe
    ]:
    /** The module is named by its whole interface — the slice's body is its widths — so two masters with different bus
      * shapes do not share one slice.
      */
    override def moduleName(parameter: LiteBusRegisterSliceParameter): String =
        s"LiteBusRegisterSlice_addrWidth${parameter.addrWidth}_dataWidth${parameter.dataWidth}" +
            s"_userWidth${parameter.userWidth}_idWidth${parameter.idWidth}"

    def architecture(parameter: LiteBusRegisterSliceParameter) =
        val io = summon[Interface[LiteBusRegisterSliceIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val validReg = RegInit(false.B)
        val bitsReg = Reg(new LiteReq(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

        // Elastic: accept while empty, and in the cycle the held request is taken (a handover, so throughput is one per
        // cycle). Ready is the slice's own state — nothing downstream reaches back into the master's valid.
        io.in.req.ready := !validReg | io.out.req.ready
        io.out.req.valid := validReg
        io.out.req.bits.addr := bitsReg.addr
        io.out.req.bits.wdata := bitsReg.wdata
        io.out.req.bits.wen := bitsReg.wen
        io.out.req.bits.wstrb := bitsReg.wstrb
        io.out.req.bits.user := bitsReg.user
        io.out.req.bits.id := bitsReg.id

        val inFire = io.in.req.valid & io.in.req.ready
        val outFire = io.out.req.valid & io.out.req.ready
        when(inFire) {
            bitsReg.addr := io.in.req.bits.addr
            bitsReg.wdata := io.in.req.bits.wdata
            bitsReg.wen := io.in.req.bits.wen
            bitsReg.wstrb := io.in.req.bits.wstrb
            bitsReg.user := io.in.req.bits.user
            bitsReg.id := io.in.req.bits.id
            validReg := true.B
        }.otherwise {
            when(outFire) {
                validReg := false.B
            }
        }

        // Response: straight through; the fabric drives valid/bits and the master drives ready.
        io.in.resp.valid := io.out.resp.valid
        io.in.resp.bits :<>= io.out.resp.bits
        io.out.resp.ready := io.in.resp.ready
