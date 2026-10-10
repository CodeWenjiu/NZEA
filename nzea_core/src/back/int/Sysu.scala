package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class SysuParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[SysuParameter] = upickle.default.macroRW

class SysuLayers(parameter: SysuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class SysuIO(parameter: SysuParameter) extends HWBundle(parameter):
    val op = Flipped(UInt(SysuOp.width))
    val rs1 = Flipped(UInt(parameter.width))
    val csrRdata = Flipped(UInt(parameter.width))
    val csrType = Flipped(UInt(CsrType.width))
    val data = Aligned(UInt(parameter.width))
    val leaves = Aligned(Bool())
    val write = Aligned(Bool())

class SysuProbe(parameter: SysuParameter) extends DVBundle[SysuParameter, SysuLayers](parameter)

/** System unit: the CSR value an instruction leaves behind, and whether the commit writes the CSR at all.
  *
  * The read data is already in the uop — the CSR file is read combinationally while the instruction issues — so this
  * unit only combines it. Its operand is the slot the form filled: rs1 for the register forms, the immediate for the
  * immediate forms.
  *
  * `data` is the CSR's post-state for every form the unit runs — a set or clear with a zero source combines to the
  * value it read — so a reader behind the uop takes `data` whenever `leaves` says the CSR stays defined. `write` is
  * the commit's own refinement of that: whether the file is stored at all, which only a zero-source CSRRS/CSRRC
  * falsifies.
  *
  * Combinational, and it carries no valid: the path gates `data`, `leaves` and `write` with the tag it issued.
  */
@generator
object Sysu extends Generator[SysuParameter, SysuLayers, SysuIO, SysuProbe]:
    override def moduleName(parameter: SysuParameter): String = "Sysu"

    def architecture(parameter: SysuParameter) =
        val io = summon[Interface[SysuIO]]

        val width = parameter.width
        val op: Referable[UInt] = io.op
        def isOp(sysu: SysuOp): Node[Bool] = op === sysu.lit.U(SysuOp.width)
        val rs1Bits: Node[Bits] = Node(io.rs1.asBits)
        val csrRdataBits: Node[Bits] = Node(io.csrRdata.asBits)

        // The value the instruction offers the CSR: the source itself for the write forms, the read data combined with the
        // source for set and clear.
        val data: Referable[UInt] = muxCase(
            0.U(width),
            Seq[(Referable[Bool], Referable[UInt])](
                (isOp(SysuOp.CSRRW), io.rs1),
                (isOp(SysuOp.CSRRWI), io.rs1),
                (isOp(SysuOp.CSRRS), (csrRdataBits | rs1Bits).asUInt),
                (isOp(SysuOp.CSRRSI), (csrRdataBits | rs1Bits).asUInt),
                (isOp(SysuOp.CSRRC), (csrRdataBits & ~rs1Bits).asUInt),
                (isOp(SysuOp.CSRRCI), (csrRdataBits & ~rs1Bits).asUInt)
            )
        )

        // The six CSR ops leave the addressed CSR defined. The ops above them (ECALL, EBREAK, FENCE) touch no CSR: their
        // address field is not an address, even when it happens to alias one.
        val leaves = (io.csrType =/= CsrType.None.lit.U(CsrType.width)) & (op < SysuOp.ECALL.lit.U(SysuOp.width))

        // A CSRRS/CSRRC with a zero source reads without writing, which is not the same as writing the old value back —
        // but a reader cannot tell the difference, because `data` is the post-state either way. So the refinement sits
        // on `write` alone, where the commit and the trace read it.
        val csrRw = isOp(SysuOp.CSRRS) | isOp(SysuOp.CSRRC) | isOp(SysuOp.CSRRSI) | isOp(SysuOp.CSRRCI)
        val write = leaves & (csrRw ? (io.rs1 =/= 0.U(width), true.B))

        io.data := data
        io.leaves := leaves
        io.write := write
