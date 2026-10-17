package nzea_cache.bank

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.CacheStorageKind
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class CacheStorageParameter(
    sets: Int,
    ways: Int,
    rowWidth: Int,
    tagBits: Int,
    lineBits: Int,
    storage: CacheStorageKind
) extends Parameter:
    require(sets >= 2, s"sets must be >= 2, got $sets")
    require(ways >= 2, s"ways must be >= 2, got $ways")
    require(rowWidth >= 8, s"rowWidth must be >= 8, got $rowWidth")
    require(tagBits >= 1, s"tagBits must be >= 1, got $tagBits")
    require(lineBits >= 1, s"lineBits must be >= 1, got $lineBits")
    require(rowWidth >= tagBits + lineBits, s"the row cannot hold a $tagBits-bit tag and a $lineBits-bit line")

    require(
        storage != CacheStorageKind.Macro(SyncRamPorts.OneR1RW) &&
            storage != CacheStorageKind.Macro(SyncRamPorts.OneW1RW) &&
            storage != CacheStorageKind.Macro(SyncRamPorts.TwoRW),
        s"the storage macro's port shape is one of 1rw, 1r1w, got ${storage.name}"
    )

given upickle.default.ReadWriter[CacheStorageParameter] = upickle.default.macroRW

class CacheStorageLayers(parameter: CacheStorageParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CacheStorageIO(parameter: CacheStorageParameter) extends HWBundle(parameter):
    private val setBits = log2Ceil(parameter.sets)
    private val wayBits = log2Ceil(parameter.ways)

    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The read port: the set whose ways' rows come out. A read-write port reads whenever no write is asked. */
    val rdSet = Flipped(UInt(setBits))
    val rdEn = Flipped(Bool())
    val rdRows = Aligned(Vec(parameter.ways, UInt(parameter.rowWidth)))

    val wrEn = Flipped(Bool())
    val wrSet = Flipped(UInt(setBits))
    val wrWay = Flipped(UInt(wayBits))
    val wrData = Flipped(UInt(parameter.rowWidth))

    /** Valid bits: read by set for the hit compare, written per way on install. */
    val vdSet = Flipped(UInt(setBits))
    val vdBits = Aligned(Vec(parameter.ways, Bool()))
    val vdWrEn = Flipped(Bool())
    val vdWrSet = Flipped(UInt(setBits))
    val vdWrWay = Flipped(UInt(wayBits))
    val vdWrVal = Flipped(Bool())

    /** Dirty bits: read by set, written per way. A read-only cache never sets them. */
    val dtSet = Flipped(UInt(setBits))
    val dtBits = Aligned(Vec(parameter.ways, Bool()))
    val dtWrEn = Flipped(Bool())
    val dtWrSet = Flipped(UInt(setBits))
    val dtWrWay = Flipped(UInt(wayBits))
    val dtWrVal = Flipped(Bool())

class CacheStorageProbe(parameter: CacheStorageParameter)
    extends DVBundle[CacheStorageParameter, CacheStorageLayers](parameter)

/** One set-associative cache's arrays: a row (tag above line) per way, and the valid — plus, for a write-back cache,
  * dirty — bits beside them in flops. The valid bits are flops either way: the hit compare reads them combinationally in
  * the same cycle the rows come out, which a synchronous read cannot do. A flop array's write lands one cycle after its
  * command, and its valid and dirty bits land with it.
  */
@generator
object CacheStorage extends Generator[CacheStorageParameter, CacheStorageLayers, CacheStorageIO, CacheStorageProbe]:

    /** The module is named by its shape — the geometry is the body — so two caches do not share one storage. */
    override def moduleName(parameter: CacheStorageParameter): String =
        s"CacheStorage_sets${parameter.sets}_ways${parameter.ways}" +
            s"_rowWidth${parameter.rowWidth}_tag${parameter.tagBits}_line${parameter.lineBits}" +
            s"_storage${parameter.storage.name}"

    def architecture(parameter: CacheStorageParameter) =
        val io = summon[Interface[CacheStorageIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val setBits = log2Ceil(parameter.sets)
        val wayBits = log2Ceil(parameter.ways)

        parameter.storage match
            case CacheStorageKind.Macro(ports) =>
                // One memory per way, all read with the same set: the ways come out together for the tag compare.
                val rows = (0 until parameter.ways).map: way =>
                    val selected = io.wrEn & (io.wrWay === way.U(wayBits))
                    val ram = SyncRam.instantiate(
                        SyncRamParameter(parameter.rowWidth, parameter.sets, ports, SyncRamReadUnderWrite.ReadFirst)
                    )
                    ram.io.clock := io.clock
                    ports match
                        case SyncRamPorts.OneR1W =>
                            ram.io.rEn := io.rdEn
                            ram.io.rAddr := io.rdSet
                            ram.io.wEn := selected
                            ram.io.wAddr := io.wrSet
                            ram.io.wData := io.wrData
                        case SyncRamPorts.OneRW =>
                            // The write owns the shared address; the read of a write cycle is discarded above, because
                            // no read is asked for while a write is.
                            ram.io.addr := selected ? (io.wrSet, io.rdSet)
                            ram.io.wEn := selected
                            ram.io.wData := io.wrData
                            ram.io.wMask := fill(parameter.rowWidth / 8, true.B).asUInt
                        case other =>
                            // Unreachable: the parameter's requirement admits only 1r1w and 1rw.
                            throw new IllegalArgumentException(s"CacheStorage port shape ${other.name} is not admitted")
                    ram.io.rData
                for way <- 0 until parameter.ways do io.rdRows.ref(way) := rows(way)

            case CacheStorageKind.Registers =>
                // One flop array per way. The write lands a cycle after its command: a flop array's write data is a
                // broadcast — every flop wants every bit — so left whole, one net would drive `sets * ways` data pins
                // (257 in the tile's 64x4 geometry, which an X1 flop cannot drive: the net's own slew, not the logic,
                // sets the cache's frequency). The row is copied per way instead, so each copy drives `sets` data pins
                // and the source — the refill engine's line — keeps a handful of loads; the command is registered for
                // the same reason. Nothing reads the entry in between: the miss's answer comes from the engine's own
                // bypass, and the next request reads only after `done` has risen.
                val arrays = (0 until parameter.ways).map(_ => Reg(Vec(parameter.sets, UInt(parameter.rowWidth))))
                val wrCmdEn = regNext(io.wrEn, false.B)
                val wrCmdSet = regNext(io.wrSet)
                val wrCmdWay = regNext(io.wrWay)
                val wrRow = (0 until parameter.ways).map(_ => Reg(UInt(parameter.rowWidth)))
                // Each copy loads only when its own way is the target, which also keeps the copies distinct: identical
                // registers would be merged back into the one broadcast they exist to avoid.
                for way <- 0 until parameter.ways do
                    when(io.wrEn & (io.wrWay === way.U(wayBits))) { wrRow(way) := io.wrData }
                for way <- 0 until parameter.ways do
                    io.rdRows.ref(way) := arrays(way).ref(io.rdSet)
                    for set <- 0 until parameter.sets do
                        when(wrCmdEn & (wrCmdWay === way.U(wayBits)) & (wrCmdSet === set.U(setBits))) {
                            arrays(way)(set) := wrRow(way)
                        }

        // The valid bits: a set's ways are one mask, so a write is one masked update of that set. A flop array's
        // lands with its row — a cycle after the command — so an entry never shows a row and a valid bit from
        // different cycles; a macro's write is its own cycle.
        val registers = parameter.storage == CacheStorageKind.Registers
        val valids = Reg(Vec(parameter.sets, UInt(parameter.ways)))
        when(!summon[ResetScope].reset.asBool) {
            for s <- 0 until parameter.sets do valids(s) := 0.U(parameter.ways)
        }
        for w <- 0 until parameter.ways do io.vdBits.ref(w) := Node(valids.ref(io.vdSet).asBits).bit(w)
        val vdWrEn: Referable[Bool] = if registers then regNext(io.vdWrEn, false.B) else io.vdWrEn
        val vdWrSet: Referable[UInt] = if registers then regNext(io.vdWrSet) else io.vdWrSet
        val vdWrWay: Referable[UInt] = if registers then regNext(io.vdWrWay) else io.vdWrWay
        val vdWrVal: Referable[Bool] = if registers then regNext(io.vdWrVal, false.B) else io.vdWrVal
        val vdMask = muxTree(vdWrWay, (0 until parameter.ways).map(w => (BigInt(1) << w).U(parameter.ways)))
        when(vdWrEn) {
            for s <- 0 until parameter.sets do
                when(vdWrSet === s.U(setBits)) {
                    val bits = Node(valids(s).asBits)
                    val next = vdWrVal ? (bits | vdMask.asBits, bits & ~vdMask.asBits)
                    valids(s) := next.asUInt
                }
        }

        // The dirty bits, on the same terms as the valid ones.
        val dirt = Reg(Vec(parameter.sets, UInt(parameter.ways)))
        when(!summon[ResetScope].reset.asBool) {
            for s <- 0 until parameter.sets do dirt(s) := 0.U(parameter.ways)
        }
        for w <- 0 until parameter.ways do io.dtBits.ref(w) := Node(dirt.ref(io.dtSet).asBits).bit(w)
        val dtWrEn: Referable[Bool] = if registers then regNext(io.dtWrEn, false.B) else io.dtWrEn
        val dtWrSet: Referable[UInt] = if registers then regNext(io.dtWrSet) else io.dtWrSet
        val dtWrWay: Referable[UInt] = if registers then regNext(io.dtWrWay) else io.dtWrWay
        val dtWrVal: Referable[Bool] = if registers then regNext(io.dtWrVal, false.B) else io.dtWrVal
        val dtMask = muxTree(dtWrWay, (0 until parameter.ways).map(w => (BigInt(1) << w).U(parameter.ways)))
        when(dtWrEn) {
            for s <- 0 until parameter.sets do
                when(dtWrSet === s.U(setBits)) {
                    val bits = Node(dirt(s).asBits)
                    val next = dtWrVal ? (bits | dtMask.asBits, bits & ~dtMask.asBits)
                    dirt(s) := next.asUInt
                }
        }
