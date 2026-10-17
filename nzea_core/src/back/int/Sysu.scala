package nzea_core.back.int

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.{ExecSlot, FuView}
import nzea_core.config.CoreConfig
import nzea_core.isa.CsrType
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class SysuParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[SysuParameter] = upickle.default.macroRW

class SysuLayers(parameter: SysuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class SysuInput(width: Int) extends Bundle:
    val op = Aligned(UInt(SysuOp.width))
    val rs1 = Aligned(UInt(width))
    val csrRdata = Aligned(UInt(width))
    val csrType = Aligned(UInt(CsrType.width))

object SysuInput:

    /** The sysu's view of the uop's union: the pool's first two slots — the form places the value the commit offers the
      * file there, and the CSR's old value in the second — and the payload's CSR code.
      */
    given FuView[SysuInput] with

        def view(u: Referable[ExecSlot], config: CoreConfig)(using
            Arena,
            Context,
            Block,
            sourcecode.File,
            sourcecode.Line,
            sourcecode.Name.Machine,
            InstanceContext,
            TypeImpl
        ): Wire[SysuInput] =
            val in = Wire(new SysuInput(config.width))
            in.op := Encoding.take(IntOp.set(config.isa).payload(u.op), SysuOp.width)
            in.rs1 := u.src.ref(0)
            in.csrRdata := u.src.ref(1)
            in.csrType := u.csr_type
            in

class SysuIO(parameter: SysuParameter) extends HWBundle(parameter):
    val in = Flipped(new SysuInput(parameter.width))
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
  * `data` is the CSR's post-state for every form the unit runs, so a reader behind the uop takes it whenever `leaves`
  * says the CSR stays defined; `write` is the commit's own refinement of that, which only a zero-source CSRRS/CSRRC
  * falsifies. Combinational, and it carries no valid: the path gates `data`, `leaves` and `write` with the tag it
  * issued.
  */
@generator
object Sysu extends Generator[SysuParameter, SysuLayers, SysuIO, SysuProbe]:
    override def moduleName(parameter: SysuParameter): String = "Sysu"

    def architecture(parameter: SysuParameter) =
        val io = summon[Interface[SysuIO]]

        val width = parameter.width
        val op: Referable[UInt] = io.in.op
        def isOp(sysu: SysuOp): Node[Bool] = op === sysu.lit.U(SysuOp.width)
        val rs1Bits: Node[Bits] = Node(io.in.rs1.asBits)
        val csrRdataBits: Node[Bits] = Node(io.in.csrRdata.asBits)

        val data: Referable[UInt] = muxCase(
            0.U(width),
            Seq[(Referable[Bool], Referable[UInt])](
                (isOp(SysuOp.CSRRW), io.in.rs1),
                (isOp(SysuOp.CSRRWI), io.in.rs1),
                (isOp(SysuOp.CSRRS), (csrRdataBits | rs1Bits).asUInt),
                (isOp(SysuOp.CSRRSI), (csrRdataBits | rs1Bits).asUInt),
                (isOp(SysuOp.CSRRC), (csrRdataBits & ~rs1Bits).asUInt),
                (isOp(SysuOp.CSRRCI), (csrRdataBits & ~rs1Bits).asUInt)
            )
        )

        // The six CSR ops leave the addressed CSR defined; a code of none — the form named no CSR, or the address mapped
        // to no implemented one — leaves nothing. The read already gated the code that way, so the test is the payload's
        // own fact, not this unit's op decode.
        val leaves = io.in.csrType =/= CsrType.None.lit.U(CsrType.width)

        // A CSRRS/CSRRC with a zero source reads without writing, which is not the same as writing the old value back —
        // but a reader cannot tell the difference, because `data` is the post-state either way. So the refinement sits
        // on `write` alone, where the commit and the trace read it.
        val csrRw = isOp(SysuOp.CSRRS) | isOp(SysuOp.CSRRC) | isOp(SysuOp.CSRRSI) | isOp(SysuOp.CSRRCI)
        val write = leaves & (csrRw ? (io.in.rs1 =/= 0.U(width), true.B))

        io.data := data
        io.leaves := leaves
        io.write := write
