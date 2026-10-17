package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.stdlib.Valid
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{ExecSlot, FuView, IssueSlot}
import nzea_core.isa.Isa
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class MulInput(config: CoreConfig) extends Bundle:
    val opA = Aligned(UInt(config.width))
    val opB = Aligned(UInt(config.width))
    val op = Aligned(UInt(MulOp.set(config.isa).width))

object MulInput:

    given FuView[MulInput] with

        def view(u: Referable[ExecSlot], config: CoreConfig)(using
            Arena,
            Context,
            Block,
            sourcecode.File,
            sourcecode.Line,
            sourcecode.Name.Machine,
            InstanceContext,
            TypeImpl
        ): Wire[MulInput] =
            val in = Wire(new MulInput(config))
            in.opA := u.src.ref(0)
            in.opB := u.src.ref(1)
            in.op := Encoding.take(IntOp.set(config.isa).payload(u.op), MulOp.set(config.isa).width)
            in

case class MulParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[MulParameter] = upickle.default.macroRW

class MulLayers(parameter: MulParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class MulIO(parameter: MulParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val issue = Flipped(new IssueSlot(new MulInput(parameter.config)))
    val done = Aligned(Bool())
    val data = Aligned(UInt(parameter.config.width))

class MulProbe(parameter: MulParameter) extends DVBundle[MulParameter, MulLayers](parameter)

/** Pipelined multiplier: radix-4 Booth partial products, a Wallace-tree compression, and one final carry-propagate
  * add — the legacy `MUL` FU's three arithmetic stages (`legacy/nzea_core/src/back/int/MUL.scala`), rebuilt on
  * today's issue/done contract, with the result select as the last stage's combinational tail.
  *
  * The point of the shape is that no cycle carries a full-width carry propagation through a loop. The Booth stage is
  * select, invert and wiring only — a negative digit's partial product is the bitwise inverse of its magnitude plus a
  * `1` at the row's own offset, summed as one extra Wallace row (the correction row), so no negation adder sits on the
  * select cone — and the only carry chain in the unit is the single CPA of the last arithmetic stage.
  *
  * The unit owns its own pipeline: `first` starts a uop, the stage valids shift it down the line, and `done` reports
  * it on the fourth cycle. The middle holds the uop for the whole residence, so a uop that disappears is a flush — the
  * valids clear in that same cycle and no completion is ever reported for it.
  */
@generator
object Mul extends Generator[MulParameter, MulLayers, MulIO, MulProbe]:
    override def moduleName(parameter: MulParameter): String = "Mul"

    def architecture(parameter: MulParameter) =
        val io = summon[Interface[MulIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width
        val productWidth = 2 * width
        val ops = MulOp.set(parameter.config.isa)
        val hasWord = ops.contains(MulOp.Mulw)

        // Radix-4 Booth: width/2 + 1 overlapping 3-bit digits, the top one reading two bits past the operand — the
        // sign extension of a signed multiplier, zero of an unsigned one. The sign semantics mirror the ops: MULHU has
        // both operands unsigned, MULHSU only the multiplicand signed, and MULW — whose product is wanted low — is
        // computed signed like MUL, the low bits not caring either way.
        val digits = width / 2 + 1

        val u = io.issue.bits
        val aBits: Node[Bits] = Node(u.opA.asBits)
        val bBits: Node[Bits] = Node(u.opB.asBits)

        val mulw = if hasWord then (u.op === MulOp.Mulw.lit.U(ops.width)) else false.B
        val aSigned = (u.op === MulOp.Mul.lit.U(ops.width)) | (u.op === MulOp.Mulh.lit.U(ops.width)) |
            (u.op === MulOp.Mulhsu.lit.U(ops.width)) | mulw
        val bSigned = (u.op === MulOp.Mul.lit.U(ops.width)) | (u.op === MulOp.Mulh.lit.U(ops.width)) | mulw

        val aExt: Node[UInt] = aSigned ? (
            (fill(productWidth - width, aBits.bit(width - 1)) ## aBits).asUInt,
            (BigInt(0).B(productWidth - width) ## aBits).asUInt
        )
        val twoA: Node[UInt] = (aExt.asBits << 1).bits(productWidth - 1, 0).asUInt
        val bExt: Node[Bits] = bSigned ? (
            fill(2, bBits.bit(width - 1)) ## bBits,
            BigInt(0).B(2) ## bBits
        )

        /** The Booth stage: each digit picks its magnitude off `{0, A, 2A}` and its sign as an invert bit plus a
          * correction bit at the row's own offset — the stage holds no adder.
          */
        def boothRows() =
            val zero: Referable[UInt] = BigInt(0).U(productWidth)
            val rows = (0 until digits).map(i => {
                val b0: Referable[Bool] = if i == 0 then false.B else bExt.bit(2 * i - 1)
                val b1: Referable[Bool] = bExt.bit(2 * i)
                val b2: Referable[Bool] = bExt.bit(2 * i + 1)
                val digit: Node[UInt] = (b2.asBits ## b1.asBits ## b0.asBits).asUInt
                // Sign of the digit's multiple: the four highest digits are negative, except 111 which is zero.
                val negate: Node[Bool] = b2 & !(b1 & b0)
                val magnitude: Node[UInt] =
                    muxTree(digit, Seq(zero, aExt, aExt, twoA, twoA, aExt, aExt, zero))
                val inverted: Node[Bits] = Node(magnitude.asBits ^ fill(productWidth, negate))
                val row: Node[UInt] = (inverted << (2 * i)).bits(productWidth - 1, 0).asUInt
                (row, negate)
            })
            // The correction row: each negative row contributes its `+1` at bit 2i, the rows' offsets being disjoint
            // makes the sum a plain wiring.
            val corrPairs = (digits - 1 to 0 by -1).map(i => (BigInt(0).B(1) ## rows(i)._2.asBits))
            val corr: Node[Bits] = BigInt(0).B(productWidth - 2 * digits) ## corrPairs.reduce((hi, lo) => hi ## lo)
            (rows.map(_._1), corr)

        /** FA/HA composition: sum plus a carry moved one position up, at the product's width. */
        def wallaceFa(a: Node[Bits], b: Node[Bits], c: Node[Bits]): (Node[Bits], Node[Bits]) =
            val sum: Node[Bits] = Node(a ^ b ^ c)
            val carry: Node[Bits] = ((a & b) | (a & c) | (b & c)).bits(productWidth - 2, 0) ## BigInt(0).B(1)
            (sum, carry)

        def wallaceHa(a: Node[Bits], b: Node[Bits]): (Node[Bits], Node[Bits]) =
            val sum: Node[Bits] = Node(a ^ b)
            val carry: Node[Bits] = (a & b).bits(productWidth - 2, 0) ## BigInt(0).B(1)
            (sum, carry)

        /** The compression: rows in, one sum/carry pair out — no carry propagates within the tree. */
        def wallaceReduce(rows0: Seq[Node[Bits]]): (Node[Bits], Node[Bits]) =
            var rows = rows0.toVector
            while rows.size > 2 do
                val next = scala.collection.mutable.ArrayBuffer[Node[Bits]]()
                var i = 0
                while i + 2 < rows.size do
                    val (sum, carry) = wallaceFa(rows(i), rows(i + 1), rows(i + 2))
                    next += sum
                    next += carry
                    i += 3
                if i + 1 < rows.size then
                    val (sum, carry) = wallaceHa(rows(i), rows(i + 1))
                    next += sum
                    next += carry
                else if i < rows.size then next += rows(i)
                rows = next.toVector
            if rows.size == 1 then (rows(0), Node(BigInt(0).B(productWidth)))
            else (rows(0), rows(1))

        val (boothRowsOut, correction) = boothRows()

        val rowRegs = Reg(Vec(digits + 1, UInt(productWidth)))
        val opReg = Reg(UInt(ops.width))
        val sumReg = Reg(UInt(productWidth))
        val carryReg = Reg(UInt(productWidth))
        val productReg = Reg(UInt(productWidth))

        val ppValid = RegInit(false.B)
        val scValid = RegInit(false.B)
        val prodValid = RegInit(false.B)

        when(!io.issue.valid) {
            ppValid := false.B
            scValid := false.B
            prodValid := false.B
        }.otherwise {
            ppValid := io.issue.first
            scValid := ppValid
            prodValid := scValid
        }

        when(io.issue.first) {
            for i <- 0 until digits do rowRegs(i) := boothRowsOut(i)
            rowRegs(digits) := correction.asUInt
            opReg := u.op
        }

        // The Wallace stage: compress the digit rows plus the correction row into one sum/carry pair.
        val rowRefs: Seq[Node[Bits]] = (0 until digits + 1).map(i => Node(rowRegs.ref(i).asBits))
        val (wallaceSum, wallaceCarry) = wallaceReduce(rowRefs)
        when(ppValid) {
            sumReg := wallaceSum.asUInt
            carryReg := wallaceCarry.asUInt
        }

        val product: Node[UInt] = prefixAdder(sumReg, carryReg, false.B, productWidth)
        when(scValid) {
            productReg := product
        }

        // The result select: MULW is MUL's low word, sign-extended; the high forms are the product's high word.
        val productBits: Node[Bits] = Node(productReg.asBits)
        val low: Node[Bits] = productBits.bits(width - 1, 0)
        val high: Node[Bits] = productBits.bits(productWidth - 1, width)
        val lowWord: Node[Bits] =
            if hasWord then fill(width - 32, low.bit(31)) ## low.bits(31, 0) else low

        def data(v: MulOp): Referable[Bits] = v match
            case MulOp.Mul                               => low
            case MulOp.Mulh | MulOp.Mulhsu | MulOp.Mulhu => high
            case MulOp.Mulw                              => lowWord

        io.done := prodValid
        io.data := mux1H(opReg, ops.variants.map(data), width).asUInt
