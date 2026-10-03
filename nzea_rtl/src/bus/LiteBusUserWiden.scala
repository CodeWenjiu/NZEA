package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Combinational adapter: a read/write master that speaks a narrow `user` payload into a fabric that carries the map's
  * widest one.
  *
  * Every master port on a fabric carries the same payload — the map's widest — so a master whose `user` field is
  * narrower is widened here rather than at every slave. The request zero-extends the field; the response narrows it
  * back, which is lossless because a slave echoes the request's own field. The handshakes pass straight through, so the
  * adapter costs no cycle.
  */
case class LiteBusUserWidenParameter(
    addrWidth: Int,
    dataWidth: Int,
    narrowUserWidth: Int,
    wideUserWidth: Int,
    idWidth: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(narrowUserWidth >= 1, s"narrowUserWidth must be >= 1, got $narrowUserWidth")
    require(wideUserWidth >= narrowUserWidth, s"wideUserWidth must be >= narrowUserWidth, got $wideUserWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[LiteBusUserWidenParameter] = upickle.default.macroRW

class LiteBusUserWidenLayers(parameter: LiteBusUserWidenParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusUserWidenIO(parameter: LiteBusUserWidenParameter) extends HWBundle(parameter):

    /** The narrow side: the master that speaks the smaller payload, so the adapter is its slave. */
    val narrow =
        Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.narrowUserWidth, parameter.idWidth))

    /** The wide side: the fabric port the adapter drives. */
    val wide = Aligned(
        new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.wideUserWidth, parameter.idWidth)
    )

class LiteBusUserWidenProbe(parameter: LiteBusUserWidenParameter)
    extends DVBundle[LiteBusUserWidenParameter, LiteBusUserWidenLayers](parameter)

@generator
object LiteBusUserWiden
    extends Generator[LiteBusUserWidenParameter, LiteBusUserWidenLayers, LiteBusUserWidenIO, LiteBusUserWidenProbe]:
    override def moduleName(parameter: LiteBusUserWidenParameter): String = "LiteBusUserWiden"

    def architecture(parameter: LiteBusUserWidenParameter) =
        val io = summon[Interface[LiteBusUserWidenIO]]

        // Request direction: the narrow side drives, the payload is zero-extended to the fabric's width.
        io.wide.req.valid := io.narrow.req.valid
        io.wide.req.bits.addr := io.narrow.req.bits.addr
        io.wide.req.bits.wdata := io.narrow.req.bits.wdata
        io.wide.req.bits.wen := io.narrow.req.bits.wen
        io.wide.req.bits.wstrb := io.narrow.req.bits.wstrb
        io.wide.req.bits.user := Node(io.narrow.req.bits.user.asBits).pad(parameter.wideUserWidth).asUInt
        io.wide.req.bits.id := io.narrow.req.bits.id
        io.narrow.req.ready := io.wide.req.ready

        // Response direction: the wide side drives; the low bits are the request's own field echoed back.
        io.narrow.resp.valid := io.wide.resp.valid
        io.narrow.resp.bits.data := io.wide.resp.bits.data
        io.narrow.resp.bits.user := Node(io.wide.resp.bits.user.asBits).bits(parameter.narrowUserWidth - 1, 0).asUInt
        io.narrow.resp.bits.id := io.wide.resp.bits.id
        io.wide.resp.ready := io.narrow.resp.ready
