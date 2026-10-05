package nzea_cache

import java.lang.foreign.Arena

import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The storage's shape: the geometry's numbers, the row's fields, and the platform's storage kind. */
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

    /** The tag port: the same set's tag fields alone, so a hit compare need not wait on the line's mux. */
    val tagSet = Flipped(UInt(setBits))
    val rdTags = Aligned(Vec(parameter.ways, UInt(parameter.tagBits)))

    /** The write port: one way's whole row. */
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
  * dirty — bits beside them in flops.
  *
  * The rows come from the platform's storage kind: a synchronous memory macro, or flops where the platform has no macro
  * shaped for an L1 (a read is then a mux of the addressed set — larger, but it keeps the clock, which a deep macro's
  * read path would not). The valid bits are flops either way: the hit compare reads them combinationally in the same
  * cycle the rows come out, which a synchronous read cannot do. The tag port exists for a flop array's split read: a
  * narrow mux for the compare, the wide one for the line, so the two are different paths, not one chain.
  *
  * The control above never reads and writes in the same cycle, so one read-write port serves as well as two, and the
  * port shape only decides which body [[SyncRam]] emits.
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
                    ram.io.rData
                for way <- 0 until parameter.ways do
                    io.rdRows.ref(way) := rows(way)
                    // The macro reads whole rows, so its tag port is a slice of the same read — the split read is a flop
                    // array's.
                    io.rdTags.ref(way) := Node(rows(way).asBits).bits(parameter.lineBits + parameter.tagBits - 1, parameter.lineBits).asUInt

            case CacheStorageKind.Registers =>
                // One flop array per way; the read is the addressed set's row, the write one decoded entry, and the
                // tag port is the row's tag field alone — narrow, so the hit compare never chains behind the line mux.
                val arrays = (0 until parameter.ways).map(_ => Reg(Vec(parameter.sets, UInt(parameter.rowWidth))))
                for way <- 0 until parameter.ways do
                    io.rdRows.ref(way) := arrays(way).ref(io.rdSet)
                    io.rdTags.ref(way) :=
                        Node(arrays(way).ref(io.tagSet).asBits)
                            .bits(parameter.lineBits + parameter.tagBits - 1, parameter.lineBits)
                            .asUInt
                    for set <- 0 until parameter.sets do
                        when(io.wrEn & (io.wrWay === way.U(wayBits)) & (io.wrSet === set.U(setBits))) {
                            arrays(way)(set) := io.wrData
                        }

        // The valid bits: a set's ways are one mask, so a write is one masked update of that set.
        val valids = Reg(Vec(parameter.sets, UInt(parameter.ways)))
        when(!summon[ResetScope].reset.asBool) {
            for s <- 0 until parameter.sets do valids(s) := 0.U(parameter.ways)
        }
        for w <- 0 until parameter.ways do io.vdBits.ref(w) := Node(valids.ref(io.vdSet).asBits).bit(w)
        val vdMask = muxTree(io.vdWrWay, (0 until parameter.ways).map(w => (BigInt(1) << w).U(parameter.ways)))
        when(io.vdWrEn) {
            for s <- 0 until parameter.sets do
                when(io.vdWrSet === s.U(setBits)) {
                    val bits = Node(valids(s).asBits)
                    val next = io.vdWrVal ? (bits | vdMask.asBits, bits & ~vdMask.asBits)
                    valids(s) := next.asUInt
                }
        }

        val dirt = Reg(Vec(parameter.sets, UInt(parameter.ways)))
        when(!summon[ResetScope].reset.asBool) {
            for s <- 0 until parameter.sets do dirt(s) := 0.U(parameter.ways)
        }
        for w <- 0 until parameter.ways do io.dtBits.ref(w) := Node(dirt.ref(io.dtSet).asBits).bit(w)
        val dtMask = muxTree(io.dtWrWay, (0 until parameter.ways).map(w => (BigInt(1) << w).U(parameter.ways)))
        when(io.dtWrEn) {
            for s <- 0 until parameter.sets do
                when(io.dtWrSet === s.U(setBits)) {
                    val bits = Node(dirt(s).asBits)
                    val next = io.dtWrVal ? (bits | dtMask.asBits, bits & ~dtMask.asBits)
                    dirt(s) := next.asUInt
                }
        }
