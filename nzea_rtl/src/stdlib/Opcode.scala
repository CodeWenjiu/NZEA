package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A Scala-side opcode for a hardware field: `lit` is the value the field carries.
  *
  * The DSL has no enum type, so an opcode set is a plain Scala 3 `enum` used as the compile-time constant table and the
  * field itself stays a `UInt`. Consumers therefore compare against literals instead of matching on a typed value; the
  * win is that decode, the FUs and any host-side tool all read one definition.
  */
trait Opcode:
  def lit: Int

object Opcode:
  /** Field width for an opcode set: the bits the largest literal needs. */
  def width(opcodes: IterableOnce[Opcode]): Int = log2Ceil(opcodes.iterator.map(_.lit).max + 1)

extension (op: Opcode)

  /** The member as a hardware constant of the field's width. */
  def U(width: Int)(using Arena, Context, Block, sourcecode.File, sourcecode.Line): Const[UInt] =
    BigInt(op.lit).U(width)
