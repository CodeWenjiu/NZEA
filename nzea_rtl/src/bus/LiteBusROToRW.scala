package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Combinational adapter: a read-only master into a read/write fabric.
  *
  * The read-only side carries no write fields, so a read/write input needs the write payload tied to its inert value.
  * The handshakes pass straight through. It is the one cost of keeping [[LiteBusRO]] and [[LiteBusRW]] distinct types —
  * a read-only master that enters the crossbar is wrapped in this, and the fabric stays uniform.
  */
case class LiteBusROToRWParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

given upickle.default.ReadWriter[LiteBusROToRWParameter] = upickle.default.macroRW

class LiteBusROToRWLayers(parameter: LiteBusROToRWParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusROToRWIO(parameter: LiteBusROToRWParameter) extends HWBundle(parameter):
    val ro = Flipped(new LiteBusRO(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))
    val rw = Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class LiteBusROToRWProbe(parameter: LiteBusROToRWParameter)
    extends DVBundle[LiteBusROToRWParameter, LiteBusROToRWLayers](parameter)

@generator
object LiteBusROToRW
    extends Generator[LiteBusROToRWParameter, LiteBusROToRWLayers, LiteBusROToRWIO, LiteBusROToRWProbe]:
    override def moduleName(parameter: LiteBusROToRWParameter): String = "LiteBusROToRW"

    def architecture(parameter: LiteBusROToRWParameter) =
        val io = summon[Interface[LiteBusROToRWIO]]

        // Request direction: the read-only side drives, the write payload is inert.
        io.rw.req.valid := io.ro.req.valid
        io.rw.req.bits.addr := io.ro.req.bits.addr
        io.rw.req.bits.user := io.ro.req.bits.user
        io.rw.req.bits.id := io.ro.req.bits.id
        io.rw.req.bits.wdata := 0.U(parameter.dataWidth)
        io.rw.req.bits.wen := false.B
        io.rw.req.bits.wstrb := 0.U(parameter.dataWidth / 8)
        io.ro.req.ready := io.rw.req.ready

        // Response direction: the write side drives, the read-only side consumes.
        io.ro.resp.valid := io.rw.resp.valid
        io.ro.resp.bits :<>= io.rw.resp.bits
        io.rw.resp.ready := io.ro.resp.ready
