package nzea_core.back.mem

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The load's value out of the bus word: the addressed lane selected by the access's offset, extended by the op's size
  * and sign. The store's side of the same knowledge (the strobe and the lane-shifted data) lives beside the request it
  * describes, in [[MemPath]].
  *
  * This is shared rather than written twice because two places read the bus: the memory path answers a load while its
  * uop is still held, and the load/store queue answers one whose uop is long gone — there the entry's own `offset` and
  * `op` are the only record of how to read the word. One selection, so the two cannot drift apart.
  */
object LsuData:

    /** The in-word offset's width: `log2(lanes)`, the bits of the address that name a byte inside a bus word. */
    def offsetWidth(width: Int): Int = log2Ceil(width / 8)

    def loadWord(
        width: Int,
        busWord: Referable[UInt],
        offset: Referable[UInt],
        op: Referable[UInt],
        ops: OneHotSet[LsuOp]
    )(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Node[Bits] =
        val lanes = width / 8
        val offsetWidth = this.offsetWidth(width)
        val rdata: Node[Bits] = Node(busWord.asBits)
        val byteSel = muxTree(offset, (0 until lanes).map(i => rdata.bits(i * 8 + 7, i * 8)))
        val halfSel = muxTree(offset, (0 until lanes).map(i => rdata.bits((i & ~1) * 8 + 15, (i & ~1) * 8)))
        // A word access sits in one half of a 64-bit bus word; at xlen 32 the bus word is the word.
        val wordSel: Node[Bits] =
            if lanes > 4 then (Node(offset.asBits).bit(offsetWidth - 1) ? (rdata.bits(63, 32), rdata.bits(31, 0)))
            else rdata
        val wordSext: Node[Bits] = if lanes > 4 then (fill(width - 32, wordSel.bit(31)) ## wordSel) else wordSel
        val wordZext: Node[Bits] = if lanes > 4 then (BigInt(0).B(width - 32) ## wordSel) else wordSel
        val zero: Referable[Bits] = BigInt(0).B(width)

        def load(v: LsuOp): Referable[Bits] = v match
            case LsuOp.LB                                  => fill(width - 8, byteSel.bit(7)) ## byteSel
            case LsuOp.LH                                  => fill(width - 16, halfSel.bit(15)) ## halfSel
            case LsuOp.LW                                  => wordSext
            case LsuOp.LBU                                 => BigInt(0).B(width - 8) ## byteSel
            case LsuOp.LHU                                 => BigInt(0).B(width - 16) ## halfSel
            case LsuOp.LWU                                 => wordZext
            case LsuOp.LD                                  => rdata
            case LsuOp.SB | LsuOp.SH | LsuOp.SW | LsuOp.SD => zero
        mux1H(op, ops.variants.map(load), width)
