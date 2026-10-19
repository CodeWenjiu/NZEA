package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** What the crossing is told: the bus's shape — kept on both sides, because a narrower master is a different adapter's
  * business — and how many flops a level is synchronized through before the receiving domain believes it.
  */
case class LiteBusCdcParameter(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, syncStages: Int)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(syncStages >= 2, s"a crossing synchronizes a level through at least 2 flops, got $syncStages")

given upickle.default.ReadWriter[LiteBusCdcParameter] = upickle.default.macroRW

class LiteBusCdcLayers(parameter: LiteBusCdcParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

/** The crossing's two ends, each with its own clock and reset. The bridge is the *slave* on the near side — the side
  * the caller's own masters live on — and the *master* on the far side.
  */
class LiteBusCdcIO(parameter: LiteBusCdcParameter) extends HWBundle(parameter):
    val nearClock = Flipped(Clock())
    val nearResetN = Flipped(Reset())
    val near = Flipped(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

    val farClock = Flipped(Clock())
    val farResetN = Flipped(Reset())
    val far = Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class LiteBusCdcProbe(parameter: LiteBusCdcParameter) extends DVBundle[LiteBusCdcParameter, LiteBusCdcLayers](parameter)

/** The bus across a clock boundary: one transaction outstanding, payloads that are *held* rather than synchronized, and
  * one flip per direction that says when a payload is there.
  *
  * The mechanism (the classic toggle crossing): the sending side latches the payload into its own register and flips a
  * level; the receiving side synchronizes that level, takes the payload the flip is about, and answers with a flip of
  * its own. The payload never crosses a synchronizer — it crosses a stable register-to-register path, correct exactly
  * because one transaction is outstanding: the sender does not touch the payload again until the answer's flip is back,
  * so it has been settled for many receiver cycles by the time the receiver's flops sample it.
  *
  * What that buys over a per-bit crossing: only the two flips and the payload's own bits cross — no FIFO, no gray
  * pointers, and no assumption about the two clocks' phase relation (a phase-independent crossing, which is what a
  * design whose grids may be re-planned needs). What it does not do: hold more than one transaction — a ceiling both of
  * its users have anyway, since the memory window and the loader's port each run one access at a time.
  *
  * A one-sided reset is the crossing's own corner, and the module is built for the design's rule that there is no such
  * thing: both sides' resets are reasons of one network, so they *assert together* — a request in flight is dropped by
  * both at once, and the design's own reset is what cancels it. One side releasing later is ordinary and safe: the
  * parity the flips carry starts from zero on both sides of a common assertion, so a request sent while the far side is
  * still held is taken the moment its flops leave reset. A far side resetting *alone* is what the module does not
  * defend against: it would find the near side's flip at an arbitrary parity — a request in flight would run once more
  * (same address, same data), and a request already answered would be replayed as a spurious one.
  */
@generator
object LiteBusCdc extends Generator[LiteBusCdcParameter, LiteBusCdcLayers, LiteBusCdcIO, LiteBusCdcProbe]:
    override def moduleName(parameter: LiteBusCdcParameter): String = "LiteBusCdc"

    /** The names the body gives the crossing's registers — the contract a flow that has to name them from the outside
      * (a timing constraint, a waveform query) reads instead of restating. The body names them through this object, so
      * the two cannot drift: a rename here renames the netlist and whoever names it from outside together.
      *
      * `reqPayload`/`reqHeld` are the request's payload on either side, `reqToggle` the flip that crosses and `reqSync`
      * the flops that synchronize it; the `resp*` four are the same for the answer. `reqSync`/`respSync` are prefixes
      * of the indexed chain (`farReqSync0`, `farReqSync1`, …), the others are the name a register carries.
      */
    object Names:
        val nearReqPayload = "nearReqPayload"
        val nearReqToggle = "nearReqToggle"
        val farReqSync = "farReqSync"
        val farReqHeld = "farReqHeld"
        val farRespHeld = "farRespHeld"
        val farRespToggle = "farRespToggle"
        val nearRespSync = "nearRespSync"
        val nearRespHeld = "nearRespHeld"

    /** How many far edges the payload's capture leaves it, for a flow that has to state it: the synchronizer's own
      * depth, which is one edge short of what the body performs — `farReqHeld` is written when the *last* of the
      * synchronizing flops changes, a flop after the one that change is seen on.
      */
    def payloadEdges(syncStages: Int): Int = syncStages

    def architecture(parameter: LiteBusCdcParameter) =
        val io = summon[Interface[LiteBusCdcIO]]

        /** Name the register about to be created: a crossing is read from a capture, so its flops read as what they are
          * (`nearReqToggle`, `farRespHeld`) rather than as generated names.
          */
        def named[T](name: String)(body: sourcecode.Name.Machine ?=> T): T =
            given sourcecode.Name.Machine = sourcecode.Name.Machine(name)
            body

        final case class Near(
            reqPayload: Reg[LiteReq],
            reqToggle: Reg[Bool],
            outstanding: Reg[Bool],
            respSync: Seq[Reg[Bool]],
            respSeen: Reg[Bool],
            respHeld: Reg[LiteResp],
            respValid: Reg[Bool]
        )
        final case class Far(
            reqSync: Seq[Reg[Bool]],
            reqSeen: Reg[Bool],
            reqHeld: Reg[LiteReq],
            reqPending: Reg[Bool],
            reqAccepted: Reg[Bool],
            respHeld: Reg[LiteResp],
            respToggle: Reg[Bool]
        )

        // Registers belong to the domain whose scope they are created in; the wiring after this is the one place the two
        // sides read each other, which is what makes those reads the crossings.
        val near = ClockScope.posedge(io.nearClock):
            ResetScope.asyncActiveLow(io.nearResetN):
                Near(
                    named(Names.nearReqPayload)(Reg(io.near.req.bits.getType)),
                    named(Names.nearReqToggle)(RegInit(false.B)),
                    named("nearOutstanding")(RegInit(false.B)),
                    (0 until parameter.syncStages).map(i => named(s"${Names.nearRespSync}$i")(RegInit(false.B))),
                    named("nearRespSeen")(RegInit(false.B)),
                    named(Names.nearRespHeld)(Reg(io.near.resp.bits.getType)),
                    named("nearRespValid")(RegInit(false.B))
                )

        val far = ClockScope.posedge(io.farClock):
            ResetScope.asyncActiveLow(io.farResetN):
                Far(
                    (0 until parameter.syncStages).map(i => named(s"${Names.farReqSync}$i")(RegInit(false.B))),
                    named("farReqSeen")(RegInit(false.B)),
                    named(Names.farReqHeld)(Reg(io.far.req.bits.getType)),
                    named("farReqPending")(RegInit(false.B)),
                    named("farReqAccepted")(RegInit(false.B)),
                    named(Names.farRespHeld)(Reg(io.far.resp.bits.getType)),
                    named(Names.farRespToggle)(RegInit(false.B))
                )

        // The request: held while a transaction is in flight, and for as long as it takes the answer to come back — the
        // master is told so through `req.ready`, which is the near side being idle.
        io.near.req.ready := !near.outstanding & !near.respValid
        when(io.near.req.valid & io.near.req.ready) {
            near.reqPayload :<= io.near.req.bits
            near.reqToggle := !near.reqToggle
            near.outstanding := true.B
        }

        // The far side's view of that flip, and the capture the flip is about: the payload has been stable since before
        // the flip, so a flop samples it without any per-bit synchronization.
        for (stage, index) <- far.reqSync.zipWithIndex do
            stage := (if index == 0 then near.reqToggle else far.reqSync(index - 1))
        when(far.reqSync.last =/= far.reqSeen) {
            far.reqHeld :<= near.reqPayload
            far.reqSeen := far.reqSync.last
            far.reqPending := true.B
        }

        // The far side's own handshake with its slave — one request until it is answered.
        io.far.req.valid := far.reqPending
        io.far.req.bits :<= far.reqHeld
        io.far.resp.ready := true.B
        when(io.far.req.valid & io.far.req.ready) {
            far.reqPending := false.B
            far.reqAccepted := true.B
        }
        when(far.reqAccepted & io.far.resp.valid) {
            far.respHeld :<= io.far.resp.bits
            far.respToggle := !far.respToggle
            far.reqAccepted := false.B
        }

        // The answer back: the same discipline in the other direction, landing in the near side's own register.
        for (stage, index) <- near.respSync.zipWithIndex do
            stage := (if index == 0 then far.respToggle else near.respSync(index - 1))
        when(near.respSync.last =/= near.respSeen) {
            near.respHeld :<= far.respHeld
            near.respSeen := near.respSync.last
            near.respValid := true.B
            near.outstanding := false.B
        }

        io.near.resp.valid := near.respValid
        io.near.resp.bits :<= near.respHeld
        when(io.near.resp.valid & io.near.resp.ready) {
            near.respValid := false.B
        }
