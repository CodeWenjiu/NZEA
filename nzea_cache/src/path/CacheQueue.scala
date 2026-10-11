package nzea_cache.path

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.CacheConfig
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A cache's in-order request pipeline: the two-entry queue, the result stage the head's read is captured into, and the
  * advance that moves one to the other.
  *
  * The payload is the top port's own request bundle, so the cache that declares it decides what rides along (a store's
  * data and strobe, say) and this side never reads inside it: the bundle is moved whole. The stage holds the *read* —
  * the ways' whole rows of the head's set, and the set's valid bits — so the array's read mux ends at a register
  * instead of reaching the answer, and the lookup over it ([[CacheLookup.CacheStage]]) starts from registers too.
  *
  * The cache's own control says when the stage may advance (`promote`) and when it is finished with what it holds
  * (`slotDone` — a store's slot waits for its write, a load's for its answer).
  */
class CacheQueue[P <: Bundle](
    c: CacheConfig,
    addrWidth: Int
)(prototype: P)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext,
    ClockScope,
    ResetScope,
    TypeImpl
):
    private val rowWidth = c.rowWidth(addrWidth)

    // ── The request queue: two entries; the head's row is live on the array's read port ──
    val headValid = RegInit(false.B)
    val headReq = Reg(prototype)
    val tailValid = RegInit(false.B)
    val tailReq = Reg(prototype)

    // ── The result stage: the head's read captured, held until the access settles ──
    val stageValid = RegInit(false.B)
    val stageRows = Reg(Vec(c.ways, UInt(rowWidth)))
    val stageVd = Reg(Vec(c.ways, Bool()))
    val stageReq = Reg(prototype)
    // The stage's one-shot: the first cycle a request is in it, its compare settles the miss slot or the replacement
    // touch. The stage may be held for cycles after, and neither may fire twice.
    val stageDecided = RegInit(false.B)
    val stageSettling = stageValid & !stageDecided

    /** The room for one more request: the cache drives its top's ready with it. */
    def accept(promote: Referable[Bool]): Referable[Bool] = !headValid | !tailValid | promote

    /** One cycle of the pipeline: a successor takes the head's place, the head's read is captured into the stage, and
      * the stage lets go of what the cache has finished with.
      */
    def advance(
        promote: Referable[Bool],
        slotDone: Referable[Bool],
        inValid: Referable[Bool],
        inReq: Referable[P],
        rows: Referable[Vec[UInt]],
        vd: Referable[Vec[Bool]]
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        val acceptFire = inValid & accept(promote)
        // The head's result goes to the stage, and a successor takes its place: the tail shifting up, or a request
        // accepted the same cycle. A request entering a busy head waits in the tail instead.
        val headFromTail = promote & tailValid
        val headFromNew = acceptFire & (!headValid | (promote & !tailValid))
        when(headFromTail) {
            headValid := true.B
            headReq :<= tailReq
        }.otherwise {
            when(headFromNew) {
                headValid := true.B
                headReq :<= inReq
            }.otherwise {
                when(promote) { headValid := false.B }
            }
        }
        // The tail takes a request only when the head does not: with the head busy and held, or with it promoting and
        // the tail shifting up. A request that becomes the head directly must not also land in the tail.
        val tailFromNew = acceptFire & headValid & (!promote | tailValid)
        when(tailFromNew) {
            tailValid := true.B
            tailReq :<= inReq
        }.otherwise { when(promote & tailValid) { tailValid := false.B } }

        // The stage's decision is one-shot, and the promotion below overrides its set, so a request promoted in the
        // same cycle the previous one leaves starts undecided.
        when(stageSettling) { stageDecided := true.B }
        when(promote) {
            stageValid := true.B
            stageDecided := false.B
            stageReq :<= headReq
            for way <- 0 until c.ways do
                stageRows(way) := rows.ref(way)
                stageVd(way) := vd.ref(way)
        }.otherwise { when(slotDone) { stageValid := false.B } }
