package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One-hot selection and the round-robin grant every per-slave arbiter here is built from. */
private[bus] object Arbiter:

    /** A candidate vector as a bit vector, candidate 0 in the low bit. */
    def bits(oh: Seq[Referable[Bool]])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] =
        val bs: Seq[Node[Bits]] = oh.map(b => Node(b.asBits))
        bs.reverse.reduceLeft((x, y) => x ## y).asUInt

    /** The index of the one set candidate, `width` bits. */
    def index(oh: Seq[Referable[Bool]], width: Int)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[UInt] =
        mux1H(bits(oh), (0 until oh.size).map(i => i.U(width)), width)

    private def lowest(cands: Seq[Referable[Bool]])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Seq[Referable[Bool]] =
        var above: Referable[Bool] = false.B
        cands.map { c =>
            val grant = c & !above
            above = above | c
            grant
        }

    /** Round-robin from each slave's pointer: the lowest candidate at or above it wins, else the lowest below. */
    def grants(cand: Seq[Seq[Referable[Bool]]], rr: Seq[Referable[UInt]], mIdxW: Int)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Seq[Seq[Referable[Bool]]] =
        val ge = cand.zip(rr).map((cs, ptr) => cs.zipWithIndex.map((c, mm) => c & (mm.U(mIdxW) >= ptr)))
        val lt = cand.zip(rr).map((cs, ptr) => cs.zipWithIndex.map((c, mm) => c & (mm.U(mIdxW) < ptr)))
        val anyAbove = ge.map(_.reduce(_ | _))
        ge.zip(lt).zip(anyAbove).map((gl, above) => lowest(gl._1).zip(lowest(gl._2)).map((a, b) => above ? (a, b)))
