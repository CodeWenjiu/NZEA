package nzea_cache.bank

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.CacheLine
import nzea_cache.config.{CacheConfig, CacheStorageKind}
import nzea_cache.path.CacheLookup
import nzea_rtl.bus.*
import nzea_rtl.bus.given
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class CacheBankParameter(
    addrWidth: Int,
    dataWidth: Int,
    userWidth: Int,
    idWidth: Int,
    config: CacheConfig,
    outstanding: Int
) extends Parameter:
    require(addrWidth >= 1, s"addrWidth must be >= 1, got $addrWidth")
    require(dataWidth >= 8 && dataWidth % 8 == 0, s"dataWidth must be a whole number of bytes, got $dataWidth")
    require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
    require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
    require(config.tagBits(addrWidth) >= 1, s"the address has no room for a tag: ${config.tagBits(addrWidth)} bits")
    require(config.lineBeats(dataWidth) >= 1, s"a line of ${config.lineBytes} bytes has no beats of $dataWidth bits")
    require(outstanding >= 1, s"outstanding must be >= 1, got $outstanding")

    require(
        config.storage == CacheStorageKind.Registers,
        s"the split read is a flop array's — a narrow tag port beside the row port; a macro reads whole rows on one " +
            s"port, got ${config.storage.name}"
    )

given upickle.default.ReadWriter[CacheBankParameter] = upickle.default.macroRW

class CacheBankLayers(parameter: CacheBankParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CacheBankIO(parameter: CacheBankParameter) extends HWBundle(parameter):
    private val c = parameter.config
    private val idxBits = c.indexBits
    private val wayBits = c.wayBits
    private val rowWidth = c.rowWidth(parameter.addrWidth)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The read port: the set the cache's head names, answered with the ways' rows and the set's valid bits. */
    val rdAddr = Flipped(UInt(parameter.addrWidth))
    val rdEn = Flipped(Bool())
    val rdRows = Aligned(Vec(c.ways, UInt(rowWidth)))
    val vdBits = Aligned(Vec(c.ways, Bool()))

    /** The stage's decisions: the request it is settling (a cacheable miss to serve, a hit to touch), and the valids it
      * captured, which the victim picks a free way from.
      */
    val stageAddr = Flipped(UInt(parameter.addrWidth))
    val vd = Flipped(Vec(c.ways, Bool()))
    val miss = Flipped(Bool())
    val missCacheable = Flipped(Bool())
    val hit = Flipped(Bool())
    val hitWay = Flipped(UInt(wayBits))

    /** The cache's own write to the arrays — a store's line update; the engine's install takes priority over it. */
    val wrValid = Flipped(Bool())
    val wrSet = Flipped(UInt(idxBits))
    val wrWay = Flipped(UInt(wayBits))
    val wrData = Flipped(UInt(rowWidth))

    /** The engine's answer, the acknowledgment of the one the cache took, whether it is running at all, and the cache's
      * own gate on starting it — a data cache waits for its stores' acknowledgments to drain, so none of them is left
      * to be mistaken for a refill's beat.
      */
    val done = Aligned(Bool())
    val bypassData = Aligned(UInt(parameter.dataWidth))
    val ack = Flipped(Bool())
    val engineFree = Flipped(Bool())
    val busy = Aligned(Bool())

    /** The engine's memory side, whole: the cache shares the port with writes of its own, so the arbitration is its. */
    val bottom =
        Aligned(new LiteBusRW(parameter.addrWidth, parameter.dataWidth, parameter.userWidth, parameter.idWidth))

class CacheBankProbe(parameter: CacheBankParameter) extends DVBundle[CacheBankParameter, CacheBankLayers](parameter)

/** The array side every cache shares: the line store, the refill engine, and the control that serves a miss from memory
  * — the slot the stage's miss is recorded in, the install that writes the answered line back, and the replacement
  * state that picks its way.
  *
  * It knows nothing of what a request is beyond its address: the cache hands it the stage's settling (a cacheable miss,
  * a hit, the captured valids) and its own writes, and gets back the read, the answer and the engine's port. So an
  * instruction cache — which never writes — ties the write port off, and a data cache shares it with its stores'
  * updates, without either of them being a mode of this module.
  */
@generator
object CacheBank extends Generator[CacheBankParameter, CacheBankLayers, CacheBankIO, CacheBankProbe]:

    override def moduleName(parameter: CacheBankParameter): String =
        val c = parameter.config
        s"CacheBank_sets${c.sets}_ways${c.ways}_lineBytes${c.lineBytes}" +
            s"_${c.replacement.name}_${c.storage.name}" +
            s"_addrWidth${parameter.addrWidth}_dataWidth${parameter.dataWidth}" +
            s"_userWidth${parameter.userWidth}_idWidth${parameter.idWidth}_outstanding${parameter.outstanding}"

    def architecture(parameter: CacheBankParameter) =
        val io = summon[Interface[CacheBankIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val c = parameter.config
        val aw = parameter.addrWidth
        val idxBits = c.indexBits
        val wayBits = c.wayBits
        val tagBits = c.tagBits(aw)
        val lineBeats = c.lineBeats(parameter.dataWidth)
        val wordOffBits = c.wordOffBits(parameter.dataWidth)
        val wordBits = parameter.dataWidth
        val rowWidth = c.rowWidth(aw)
        val padBits = rowWidth - tagBits - c.lineBits

        val storage =
            CacheStorage.instantiate(CacheStorageParameter(c.sets, c.ways, rowWidth, tagBits, c.lineBits, c.storage))
        storage.io.clock := io.clock
        storage.io.resetN := io.resetN
        val refill = CacheRefill.instantiate(
            CacheRefillParameter(
                aw,
                parameter.dataWidth,
                parameter.userWidth,
                parameter.idWidth,
                lineBeats,
                wordOffBits,
                parameter.outstanding
            )
        )
        refill.io.clock := io.clock
        refill.io.resetN := io.resetN
        val repl = new ReplacementState(c.sets, c.ways, c.replacement)

        // ── The miss the engine is working on: what the install needs once it answers ──
        // The slot is written when the miss settles and stands until the engine takes it, so a miss settling in the
        // same cycle the engine finishes the previous one is not lost.
        val missPending = RegInit(false.B)
        val missAddr = Reg(UInt(aw))
        val missSet = Reg(UInt(idxBits))
        val missTag = Reg(UInt(tagBits))
        val missWay = Reg(UInt(wayBits))
        val missWordOff = Reg(UInt(wordOffBits))
        val missCacheable = RegInit(false.B)

        // ── The hit's replacement touch: registered, so the tree update starts a cycle after the compare ──
        val touchPending = RegInit(false.B)
        val touchSet = Reg(UInt(idxBits))
        val touchWay = Reg(UInt(wayBits))

        // The stage's live view: what a settling request installs, touches and victimizes.
        val stageView = CacheLookup.addrOf(c, aw, parameter.dataWidth)(io.stageAddr)
        val freeWay = muxCase(0.U(wayBits), (0 until c.ways).map(w => (!io.vd.ref(w), w.U(wayBits))))
        val hasFree = (0 until c.ways).map(w => !io.vd.ref(w)).reduce(_ | _)
        // The victim: a free way first, the replacement state only once the set is full.
        val victim = hasFree ? (freeWay, repl.way(stageView.set))

        // ── The engine ──
        // A miss is what it serves; a request outside the windows is the same stream with one beat, and the flag only
        // decides whether the line is installed.
        val missView = CacheLookup.addrOf(c, aw, parameter.dataWidth)(missAddr)
        refill.io.start := missPending & !refill.io.busy & io.engineFree
        refill.io.startAddr :=
            missCacheable ? (
                CacheLookup.lineBase(c)(missView),
                CacheLookup.wordAligned(c, parameter.dataWidth)(missView)
            )
        refill.io.startWordOff := missCacheable ? (missWordOff, 0.U(wordOffBits))
        refill.io.startUncached := !missCacheable
        refill.io.ack := io.ack
        when(refill.io.start) { missPending := false.B }

        // ── The storage's read: the head's set, live — the whole row per way ──
        val rdView = CacheLookup.addrOf(c, aw, parameter.dataWidth)(io.rdAddr)
        storage.io.rdSet := rdView.set
        storage.io.rdEn := io.rdEn
        storage.io.vdSet := rdView.set
        io.rdRows :<= storage.io.rdRows
        io.vdBits :<= storage.io.vdBits

        // ── The storage's write: the engine's install and the cache's own updates share the port ──
        // They cannot collide — a miss and a store never occupy the cache's stage together — so the install only takes
        // priority. An uncached read installs nothing: its single beat is not a line, and a line it never becomes must
        // not evict one that is.
        val installed = Wire(new CacheLine(tagBits, lineBeats, wordBits))
        installed.tag := missTag
        installed.data :<= refill.io.line.asBits.asVec(UInt(wordBits))
        val installRow = if padBits > 0 then installed.asBits.pad(rowWidth).asUInt else installed.asBits.asUInt
        val install = refill.io.install & missCacheable
        storage.io.wrEn := install | io.wrValid
        storage.io.wrSet := install ? (missSet, io.wrSet)
        storage.io.wrWay := install ? (missWay, io.wrWay)
        storage.io.wrData := install ? (installRow, io.wrData)
        storage.io.vdWrEn := install
        storage.io.vdWrSet := missSet
        storage.io.vdWrWay := missWay
        storage.io.vdWrVal := true.B
        // The caches in front of this bank are write-through: a line is never dirty, so the bits stay clear.
        storage.io.dtSet := rdView.set
        storage.io.dtWrEn := false.B
        storage.io.dtWrSet := 0.U(idxBits)
        storage.io.dtWrWay := 0.U(wayBits)
        storage.io.dtWrVal := false.B
        when(install) { repl.touch(missSet, missWay) }

        // ── The stage's decisions, once per request ──
        when(io.miss) {
            missPending := true.B
            missAddr := io.stageAddr
            missSet := stageView.set
            missTag := stageView.tag
            missWay := victim
            missWordOff := stageView.word
            missCacheable := io.missCacheable
        }
        when(io.hit) {
            touchPending := true.B
            touchSet := stageView.set
            touchWay := io.hitWay
        }.otherwise { touchPending := false.B }
        when(touchPending) { repl.touch(touchSet, touchWay) }

        // ── The answer, and the memory side the cache shares with its own writes ──
        io.done := refill.io.done
        io.bypassData := refill.io.bypassData
        io.busy := refill.io.busy
        io.bottom :<>= refill.io.bottom
