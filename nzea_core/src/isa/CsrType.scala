package nzea_core.isa

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** CSR type: `CsrType.None` = no mapping, the rest are the machine-mode CSRs this core implements.
  *
  * ISA-level: produced by decode, carried through the middle and backend, committed by the CSR file.
  */
enum CsrType(val lit: Int) extends Opcode:
  case None extends CsrType(0)
  case Mstatus extends CsrType(1)
  case Mtvec extends CsrType(2)
  case Mepc extends CsrType(3)
  case Mcause extends CsrType(4)
  case Mscratch extends CsrType(5)

object CsrType:
  val width: Int = Opcode.width(values)

  val MSTATUS_ADDR = 0x300
  val MTVEC_ADDR = 0x305
  val MSCRATCH_ADDR = 0x340
  val MEPC_ADDR = 0x341
  val MCAUSE_ADDR = 0x342

  /** Map a CSR address to its `csr_type` field; an unmapped address decodes to [[CsrType.None]]. */
  def fromAddr[LHS <: Referable[UInt]](addr: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] =
    val bits: Node[Bits] = Node(addr.asBits)
    val a = bits.bits(11, 0).asUInt
    Node(
      muxCase(
        Node(0.U(width)),
        Seq(
          (a === MSTATUS_ADDR.U(12), CsrType.Mstatus.lit.U(width)),
          (a === MTVEC_ADDR.U(12), CsrType.Mtvec.lit.U(width)),
          (a === MSCRATCH_ADDR.U(12), CsrType.Mscratch.lit.U(width)),
          (a === MEPC_ADDR.U(12), CsrType.Mepc.lit.U(width)),
          (a === MCAUSE_ADDR.U(12), CsrType.Mcause.lit.U(width))
        )
      )
    )

  /** Convert a `csr_type` field to `(addr, valid)` for the commit trace: `valid` = the commit wrote a CSR. */
  def toImmValid[LHS <: Referable[UInt]](csrType: LHS)(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): (Node[UInt], Node[Bool]) =
    val valid = Node(csrType =/= CsrType.None.lit.U(width))
    val addr = Node(
      muxCase(
        Node(0.U(12)),
        Seq(
          (csrType === CsrType.Mstatus.lit.U(width), BigInt(MSTATUS_ADDR).U(12)),
          (csrType === CsrType.Mtvec.lit.U(width), BigInt(MTVEC_ADDR).U(12)),
          (csrType === CsrType.Mepc.lit.U(width), BigInt(MEPC_ADDR).U(12)),
          (csrType === CsrType.Mcause.lit.U(width), BigInt(MCAUSE_ADDR).U(12)),
          (csrType === CsrType.Mscratch.lit.U(width), BigInt(MSCRATCH_ADDR).U(12))
        )
      )
    )
    (addr, valid)
