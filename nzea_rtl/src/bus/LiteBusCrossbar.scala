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
  * A master may hold `outstanding` requests in the fabric at once, so the fabric pipelines: a granted request is handed
  * to the slave the same cycle, and the slave is fed again as soon as it is ready instead of waiting for the previous
  * answer to come back. That is what keeps the fabric's bandwidth off its latency — one cycle per transaction once the
  * pipe is full, not one per round trip. A slave is assumed to answer in order; its answers are routed to their owners
  * through a per-slave [[ShiftFifo]] of owner ids.
  *
  * Each master owns a [[ShiftFifo]] of answers, and a slave's answer is deposited there the cycle it arrives — the
  * slave frees without waiting on the master to be ready, so a slow master cannot stall the slave it used and, through
  * it, every other master sharing that slave (head-of-line blocking). A master's answer is always the FIFO's head — a
  * register — so the answer's select and muxes are the deposit's and never reach a master's own logic; the one cycle
  * that costs is the round trip's, and it is what keeps a master's response cone out of the fabric's. At most one
  * answer lands per master per cycle: the miss (a decode miss is answered zero, immediately) outranks the slaves, and
  * the lowest-index slave outranks the rest.
  */
case class LiteBusCrossbarParameter(map: AddressMap, addrWidth: Int, dataWidth: Int, outstanding: Int)
    extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 1, s"dataWidth must be >= 1, got $dataWidth")
    require(outstanding >= 1, s"outstanding must be >= 1, got $outstanding")

    require(
        map.slaves.forall(_.outstanding <= outstanding),
        s"a slave's outstanding must not exceed the crossbar's $outstanding, got ${map.slaves.map(_.outstanding)}"
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

    /** Which slave each master's address lands in — one row per master, one column per slave. */
    private def decode(map: AddressMap, aw: Int, master: Seq[Ref[LiteBusRW]])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Seq[Seq[Node[Bool]]] =
        (0 until master.size).map { mm =>
            val addr = master(mm).req.bits.addr
            (0 until map.slaves.size).map { ss =>
                val r = map.slaves(ss).range
                val baseHit = addr >= r.base.U(aw)
                val endHit = if r.endExclusive == (BigInt(1) << aw) then true.B else addr < r.endExclusive.U(aw)
                baseHit & endHit
            }
        }

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
        val depth = parameter.outstanding
        val cntW = log2Ceil(depth + 1)
        val master = (0 until m).map(i => io.ins.ref(i))
        val slave = (0 until s).map(i => io.outs.ref(i))

        // Per slave: the queue of owners, oldest first — what routes an in-order answer back to the master that asked.
        // Its count is the slave's outstanding, so no second counter is kept.
        val owns = Seq.tabulate(s)(_ => new ShiftFifo(depth, Seq(mIdxW)))
        val rr = Seq.tabulate(s)(_ => RegInit(0.U(mIdxW)))

        // Per master: how many requests it has in the fabric, and the FIFO of answers it has not taken yet.
        val mOcc = Seq.tabulate(m)(_ => RegInit(0.U(cntW)))
        val answers = Seq.tabulate(m)(_ => new ShiftFifo(depth, Seq(dw, uw, iw)))

        val hits = decode(map, aw, master)
        val hasHit = hits.map(_.reduce(_ | _))

        // A full slave or a full master is not a candidate: it could not take the request or hold the answer.
        def slaveFull(ss: Int): Node[Bool] = owns(ss).count === depth.U(cntW)
        def masterFull(mm: Int): Node[Bool] = mOcc(mm) === depth.U(cntW)

        // A miss is answered zero without a slave, so it never touches the owner queue.
        val missAccept = (0 until m).map(mm => master(mm).req.valid & !hasHit(mm) & !masterFull(mm))

        // Answers: a slave's answer goes to its owner's FIFO and pops the owner queue. One answer per master per cycle —
        // a slave yields if a miss or an earlier slave already targets the same owner.
        val owner = (0 until s).map(ss => owns(ss).head(0))
        def ownerIs(mm: Int, ss: Int): Node[Bool] = owner(ss) === mm.U(mIdxW)
        def missTo(ss: Int): Node[Bool] = (0 until m).map(mm => missAccept(mm) & ownerIs(mm, ss)).reduce(_ | _)
        def ownerRoom(ss: Int): Node[Bool] =
            (0 until m).map(mm => ownerIs(mm, ss) & (answers(mm).count =/= depth.U(cntW))).reduce(_ | _)
        val slaveWants = (0 until s).map(ss => slave(ss).resp.valid & (owns(ss).count =/= 0.U(cntW)))
        val resp = (0 until s).map { ss =>
            val earlier = (0 until ss)
                .map(ss2 => (owner(ss2) === owner(ss)) & slaveWants(ss2))
                .reduceOption(_ | _)
                .getOrElse(false.B)
            slaveWants(ss) & !missTo(ss) & !earlier & ownerRoom(ss)
        }

        // Where each master's answer comes from this cycle: a decode miss, or the slave that owns one of its requests.
        val fromSlave = (0 until m).map(mm => (0 until s).map(ss => resp(ss) & ownerIs(mm, ss)))
        val incoming = (0 until m).map(mm => missAccept(mm) | fromSlave(mm).reduce(_ | _))
        // The answer is deposited the cycle it arrives and the master takes it the next: a master's response is the
        // FIFO's head, never the slaves' own logic. `resp(ss)` already asked for room, so a deposit always lands.
        val push = incoming
        val head = (0 until m).map(mm => answers(mm).nonEmpty)
        val pop = (0 until m).map(mm => head(mm) & master(mm).resp.ready)

        // Per-slave arbitration: candidates are masters that hit it and are free, granted round-robin from the pointer.
        val cand = (0 until s).map { ss =>
            (0 until m).map(mm => master(mm).req.valid & hits(mm)(ss) & !slaveFull(ss) & !masterFull(mm))
        }
        val grantOH = Arbiter.grants(cand, rr, mIdxW)
        val grantValid = grantOH.map(_.reduce(_ | _))
        val grantIdx = grantOH.map(oh => Arbiter.index(oh, mIdxW))

        // The granted request goes straight to the slave, and the master holds until the slave takes it — so a slave that
        // is ready every cycle is fed every cycle, and the pointer advances only on a real transfer.
        val send = (0 until s).map(ss => grantValid(ss) & slave(ss).req.ready)
        for ss <- 0 until s do
            val sel = Arbiter.bits(grantOH(ss))
            slave(ss).req.valid := grantValid(ss)
            slave(ss).req.bits.addr := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.addr), aw)
            slave(ss).req.bits.wdata := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wdata), dw)
            slave(ss).req.bits.wen := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wen))
            slave(ss).req.bits.wstrb := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.wstrb), dw / 8)
            slave(ss).req.bits.user := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.user), uw)
            slave(ss).req.bits.id := mux1H(sel, (0 until m).map(mm => master(mm).req.bits.id), iw)
            when(send(ss)) {
                rr(ss) := (grantIdx(ss) === (m - 1).U(mIdxW)) ? (0.U(mIdxW), addWrap(grantIdx(ss), 1.U(mIdxW), mIdxW))
            }
            owns(ss).pop(resp(ss))
            owns(ss).push(send(ss), resp(ss), Seq(grantIdx(ss)))
            owns(ss).updateCount(send(ss), resp(ss))
            slave(ss).resp.ready := resp(ss)

        // A master's readiness: the slave it was granted to took it, or its address missed and the miss is taken now.
        val accepted = (0 until m).map { mm =>
            (0 until s).map(ss => grantValid(ss) & (grantIdx(ss) === mm.U(mIdxW)) & slave(ss).req.ready).reduce(_ | _)
        }
        for mm <- 0 until m do
            val granted = accepted(mm) | missAccept(mm)
            master(mm).req.ready := granted
            mOcc(mm) := subWrap(
                addWrap(mOcc(mm), granted ? (1.U(cntW), 0.U(cntW)), cntW),
                pop(mm) ? (1.U(cntW), 0.U(cntW)),
                cntW
            )

        // The answer FIFOs: deposit what arrived, hand the head to the master when it takes it.
        for mm <- 0 until m do
            val pushData = muxCase(0.U(dw), (0 until s).map(ss => (fromSlave(mm)(ss), slave(ss).resp.bits.data)))
            val pushUser = muxCase(
                master(mm).req.bits.user,
                (0 until s).map(ss => (fromSlave(mm)(ss), slave(ss).resp.bits.user))
            )
            val pushId =
                muxCase(master(mm).req.bits.id, (0 until s).map(ss => (fromSlave(mm)(ss), slave(ss).resp.bits.id)))
            answers(mm).pop(pop(mm))
            answers(mm).push(push(mm), pop(mm), Seq(pushData, pushUser, pushId))
            answers(mm).updateCount(push(mm), pop(mm))

            master(mm).resp.valid := head(mm)
            master(mm).resp.bits.data := answers(mm).head(0)
            master(mm).resp.bits.user := answers(mm).head(1)
            master(mm).resp.bits.id := answers(mm).head(2)

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
