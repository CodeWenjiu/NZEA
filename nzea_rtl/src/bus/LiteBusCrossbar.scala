package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A crossbar the caller describes with an [[AddressMap]] instead of a port count and a positional range list.
  *
  * The map is the one source: the port count, the address decode and the caller's named lookup all derive from it, so
  * adding a slave is one entry in the map rather than an edit in three lists kept in step by hand. `addrWidth` /
  * `dataWidth` are common to both sides; the fabric's `userWidth` / `idWidth` are the map's widest, and a master that
  * speaks a narrower payload is adapted by its owner.
  *
  * The baseline routes one outstanding request per master and per slave (the core's masters issue id 0). The per-slave
  * `outstanding` in the map is where the owner table returns when a master needs more; it is checked to be 1 here so
  * the limit is stated rather than silently applied.
  *
  * Each master owns a one-entry response slot, and a slave's answer is deposited there the cycle it arrives — the slave
  * frees immediately and never waits on the master to be ready. Without it a master that is slow to accept would stall
  * the slave it used and, through it, every other master sharing that slave (head-of-line blocking). The slot is one
  * deep because a master holds one request at a time; the multi-outstanding version turns it into a FIFO.
  */
case class LiteBusCrossbarParameter(map: AddressMap, addrWidth: Int, dataWidth: Int) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")

    require(
        map.slaves.forall(_.outstanding == 1),
        s"the baseline crossbar holds one outstanding request per slave, got ${map.slaves.map(_.outstanding)}"
    )

given upickle.default.ReadWriter[LiteBusCrossbarParameter] = upickle.default.macroRW

class LiteBusCrossbarLayers(parameter: LiteBusCrossbarParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class LiteBusCrossbarIO(parameter: LiteBusCrossbarParameter) extends HWBundle(parameter):
    private val map = parameter.map
    private val bus = new LiteBusRW(parameter.addrWidth, parameter.dataWidth, map.userWidth, map.idWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** Master ports, in map order: the crossbar is the slave side, so the bundle is flipped. */
    val ins: BundleField[Vec[LiteBusRW]] = Flipped(Vec(map.masters.size, bus))

    /** Slave ports, in map order: the crossbar is the master side. */
    val outs: BundleField[Vec[LiteBusRW]] = Aligned(Vec(map.slaves.size, bus))

class LiteBusCrossbarProbe(parameter: LiteBusCrossbarParameter)
    extends DVBundle[LiteBusCrossbarParameter, LiteBusCrossbarLayers](parameter)

@generator
object LiteBusCrossbar
    extends Generator[LiteBusCrossbarParameter, LiteBusCrossbarLayers, LiteBusCrossbarIO, LiteBusCrossbarProbe]:
    override def moduleName(parameter: LiteBusCrossbarParameter): String = "LiteBusCrossbar"

    def architecture(parameter: LiteBusCrossbarParameter) =
        val io = summon[Interface[LiteBusCrossbarIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val map = parameter.map
        val m = map.masters.size
        val s = map.slaves.size
        val aw = parameter.addrWidth
        val dw = parameter.dataWidth
        val uw = map.userWidth
        val iw = map.idWidth
        val mIdxW = log2Ceil(m.max(2))
        val master = (0 until m).map(i => io.ins.ref(i))
        val slave = (0 until s).map(i => io.outs.ref(i))

        // Per-slave state: a request latched from a master (`pending`), then accepted by the slave (`inflight`), each
        // with the master it belongs to. One outstanding means one owner field, not a table.
        val pending = Seq.tabulate(s)(_ => RegInit(false.B))
        val inflight = Seq.tabulate(s)(_ => RegInit(false.B))
        val owner = Seq.tabulate(s)(_ => RegInit(0.U(mIdxW)))
        val latch = Seq.tabulate(s)(_ => Reg(new LiteReq(aw, dw, uw, iw)))
        val rr = Seq.tabulate(s)(_ => RegInit(0.U(mIdxW)))

        // Per-master response slot: one holding place for the answer to the master's outstanding request, whether it
        // comes back from a slave or is a decode-miss answering zero. One deep: a master holds one request at a time.
        val respValid = Seq.tabulate(m)(_ => RegInit(false.B))
        val respData = Seq.tabulate(m)(_ => Reg(UInt(dw)))
        val respUser = Seq.tabulate(m)(_ => Reg(UInt(uw)))
        val respId = Seq.tabulate(m)(_ => Reg(UInt(iw)))

        // Decode: which slave each master's address lands in.
        val hits: Seq[Seq[Node[Bool]]] = (0 until m).map { mm =>
            val addr = master(mm).req.bits.addr
            (0 until s).map { ss =>
                val r = map.slaves(ss).range
                val baseHit = addr >= r.base.U(aw)
                val endHit = if r.endExclusive == (BigInt(1) << aw) then true.B else addr < r.endExclusive.U(aw)
                baseHit & endHit
            }
        }
        val hasHit = hits.map(_.reduce(_ | _))

        // A master may hold one request in the fabric at a time.
        val allBusy = (0 until s).map(ss => pending(ss) | inflight(ss))
        def masterBusy(mm: Int): Node[Bool] =
            respValid(mm) | (0 until s).map(ss => allBusy(ss) & (owner(ss) === mm.U(mIdxW))).reduce(_ | _)

        // Per-slave arbitration: candidates are masters that hit it and are free, granted round-robin from the pointer.
        def lowestOneHot(cands: Seq[Referable[Bool]]): Seq[Referable[Bool]] =
            var above: Referable[Bool] = false.B
            cands.map { c =>
                val grant = c & !above
                above = above | c
                grant
            }

        def oneHotBits(oh: Seq[Referable[Bool]]): Node[UInt] =
            val bits: Seq[Node[Bits]] = oh.map(b => Node(b.asBits))
            bits.reverse.reduceLeft((x, y) => x ## y).asUInt

        def oneHotIndex(oh: Seq[Referable[Bool]]): Node[UInt] =
            mux1H(oneHotBits(oh), (0 until oh.size).map(i => i.U(mIdxW)), mIdxW)

        val cand = (0 until s).map { ss =>
            (0 until m).map(mm => master(mm).req.valid & hits(mm)(ss) & !allBusy(ss) & !masterBusy(mm))
        }
        val ge = (0 until s).map(ss => (0 until m).map(mm => cand(ss)(mm) & (mm.U(mIdxW) >= rr(ss))))
        val lt = (0 until s).map(ss => (0 until m).map(mm => cand(ss)(mm) & (mm.U(mIdxW) < rr(ss))))
        val anyAbove = ge.map(_.reduce(_ | _))
        val grantOH =
            (0 until s).map(ss => lowestOneHot(ge(ss)).zip(lowestOneHot(lt(ss))).map((a, b) => anyAbove(ss) ? (a, b)))
        val grantValid = grantOH.map(_.reduce(_ | _))
        val grantIdx = grantOH.map(oneHotIndex)

        // Latch the granted request and hand it to the slave; the master's ready is its grant, so nothing downstream
        // depends on the master holding its request. The pointer advances past whoever just won.
        for ss <- 0 until s do
            val sel = oneHotBits(grantOH(ss))
            val pick = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.addr), aw)
            val wdata = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wdata), dw)
            val wen = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wen))
            val wstrb = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wstrb), dw / 8)
            val user = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.user), uw)
            val id = mux1H(sel, (0 until m).map(mm => master(mm).req.bits.id), iw)

            when(grantValid(ss)) {
                latch(ss).addr := pick
                latch(ss).wdata := wdata
                latch(ss).wen := wen
                latch(ss).wstrb := wstrb
                latch(ss).user := user
                latch(ss).id := id
                owner(ss) := grantIdx(ss)
                pending(ss) := true.B
                rr(ss) := (grantIdx(ss) === (m - 1).U(mIdxW)) ? (0.U(mIdxW), addWrap(grantIdx(ss), 1.U(mIdxW), mIdxW))
            }

            slave(ss).req.valid := pending(ss)
            slave(ss).req.bits.addr := latch(ss).addr
            slave(ss).req.bits.wdata := latch(ss).wdata
            slave(ss).req.bits.wen := latch(ss).wen
            slave(ss).req.bits.wstrb := latch(ss).wstrb
            slave(ss).req.bits.user := latch(ss).user
            slave(ss).req.bits.id := latch(ss).id

            when(pending(ss) & slave(ss).req.ready) {
                pending(ss) := false.B
                inflight(ss) := true.B
            }

        // A master's readiness: it was granted a slave, or its address missed and the miss is taken now.
        val missAccept = (0 until m).map(mm => master(mm).req.valid & !hasHit(mm) & !masterBusy(mm))
        for mm <- 0 until m do
            val granted = (0 until s).map(ss => grantValid(ss) & (grantIdx(ss) === mm.U(mIdxW))).reduce(_ | _)
            master(mm).req.ready := granted | missAccept(mm)
            when(missAccept(mm)) {
                respValid(mm) := true.B
                respData(mm) := 0.U(dw)
                respUser(mm) := master(mm).req.bits.user
                respId(mm) := master(mm).req.bits.id
            }

        // The master offers whatever its slot holds and clears the slot when taken.
        for mm <- 0 until m do
            master(mm).resp.valid := respValid(mm)
            master(mm).resp.bits.data := respData(mm)
            master(mm).resp.bits.user := respUser(mm)
            master(mm).resp.bits.id := respId(mm)
            when(respValid(mm) & master(mm).resp.ready) {
                respValid(mm) := false.B
            }

        // A slave's answer is absorbed into its owner's slot the cycle it arrives, so the slave frees without waiting on
        // the master. The owner's slot is free by construction — a master with a full slot holds no request — so the
        // readiness below is really just `inflight`; the check is kept so the invariant is stated, not assumed.
        for ss <- 0 until s do
            val slotFull = (0 until m).map(mm => (owner(ss) === mm.U(mIdxW)) & respValid(mm)).reduce(_ | _)
            slave(ss).resp.ready := inflight(ss) & !slotFull
            val take = inflight(ss) & slave(ss).resp.valid & !slotFull
            when(take) {
                inflight(ss) := false.B
                for mm <- 0 until m do
                    when(owner(ss) === mm.U(mIdxW)) {
                        respValid(mm) := true.B
                        respData(mm) := slave(ss).resp.bits.data
                        respUser(mm) := slave(ss).resp.bits.user
                        respId(mm) := slave(ss).resp.bits.id
                    }
            }

/** Named ports of a crossbar: the address map turns a name into the position, so no caller counts an index. */
extension (io: Interface[LiteBusCrossbarIO])

    def in(map: AddressMap, name: String)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Ref[LiteBusRW] = io.ins.ref(map.masterIndex(name))

    def out(map: AddressMap, name: String)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Ref[LiteBusRW] = io.outs.ref(map.slaveIndex(name))
