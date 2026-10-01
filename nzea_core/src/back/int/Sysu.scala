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
    val write = Aligned(Bool())

class SysuProbe(parameter: SysuParameter) extends DVBundle[SysuParameter, SysuLayers](parameter)

/** System unit: the CSR data an instruction offers, and whether it writes the CSR at all.
  *
  * The read data is already in the uop — the CSR file is read combinationally while the instruction issues — so this
  * unit only combines it. Its operand is the slot the form filled: rs1 for the register forms, the immediate for the
  * immediate forms.
  *
  * Combinational, and it carries no valid: the path gates `data` and `write` with the tag it issued.
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

        // A CSRRS/CSRRC with a zero source reads without writing, which is not the same as writing the old value back. Only
        // the six CSR ops write at all: the address field of the others (ECALL, EBREAK, FENCE) is not a CSR address, even
        // when it happens to alias one.
        val csrRw = isOp(SysuOp.CSRRS) | isOp(SysuOp.CSRRC) | isOp(SysuOp.CSRRSI) | isOp(SysuOp.CSRRCI)
        val write = (io.csrType =/= CsrType.None.lit.U(CsrType.width)) & (op < SysuOp.ECALL.lit.U(SysuOp.width)) &
            (csrRw ? (io.rs1 =/= 0.U(width), true.B))

        io.data := data
        io.write := write
