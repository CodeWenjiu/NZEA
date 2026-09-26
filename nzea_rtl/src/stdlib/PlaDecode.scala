package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.decoder.{BitPat, PLA, TruthTable}
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A minimized decode PLA plus the field layout its tables put into it.
  *
  * Host-side plan, no hardware: built once per configuration, then used to emit the decode logic and to slice the
  * decoded word by field name. Fields sit where the tables' concatenation puts them — first table in the high bits — so
  * a field's position follows the table list instead of a hand-written bit range.
  */
final class PlaPlan private (val pla: PLA, val columns: Map[String, OpColumn]):

  /** Emit the decode: one AND term per PLA row, one OR per output bit, inverted where the default asserts.
    *
    * The inversion is the other half of [[Qmc]]'s cover rule: a bit whose default asserts is pulled low by the rows
    * that cover it, so the OR plane alone reproduces the table.
    */
  def decode(in: Node[Bits])(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bits] =
    def literal(bit: Int, value: Boolean): Node[Bool] =
      val signal: Node[Bool] = in.bit(bit)
      if value then signal else !signal

    val terms = pla.table.map: (cube, _) =>
      (0 until cube.width)
        .collect { case bit if cube.mask.testBit(bit) => literal(bit, cube.value.testBit(bit)) }
        .reduceOption(_ & _)
        .getOrElse(Node(true.B))

    val bits = Seq.tabulate(pla.outputWidth): bit =>
      val asserting = pla.table.indices.collect:
        case row if pla.table(row)._2.mask.testBit(bit) && pla.table(row)._2.value.testBit(bit) => terms(row)
      val or = asserting.reduceOption(_ | _).getOrElse(Node(false.B))
      if pla.default.mask.testBit(bit) && pla.default.value.testBit(bit) then !or else or

    Node(bits.reverse.map(_.asBits).reduce(_ ## _))

  /** One numeric field of a [[decode]] result, sliced by its table's width. */
  def field(name: String, decoded: Node[Bits])(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[UInt] = slice(name, decoded).asUInt

  /** One single-bit field of a [[decode]] result — the flags. */
  def flag(name: String, decoded: Node[Bits])(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bool] = slice(name, decoded).asBool

  private def slice(name: String, decoded: Node[Bits])(using
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
  ): Node[Bits] =
    val column = columns.getOrElse(
      name,
      throw new IllegalArgumentException(s"no decode field '$name'; the plan has ${columns.keys.mkString(", ")}")
    )
    val value: Node[UInt] = column(decoded.asUInt)
    val bits: Node[Bits] = value.asBits
    bits

  /** One line for elaboration output: how big the minimized decode is, and what it decodes into. */
  def report: String =
    val fields = columns.toSeq.sortBy((_, column) => -column.offset).map((name, column) => s"$name=${column.width}")
    s"decode: ${pla.table.length} rows, ${fields.mkString(" ")}"

object PlaPlan:

  /** Minimize `tables` and lay their fields out in the order they are concatenated. */
  def apply(tables: Seq[TruthTable]): PlaPlan =
    require(tables.nonEmpty, "PlaPlan needs at least one table")
    val pla = Qmc(tables)
    val width = tables.map(_.outputBits).sum
    require(
      pla.outputWidth == width,
      s"baked PLA width ${pla.outputWidth} does not match the tables' fields ($width)"
    )
    val columns = tables
      .foldLeft((width, Map.empty[String, OpColumn])): (state, table) =>
        val (high, acc) = state
        (high - table.outputBits, acc + (table.name -> OpColumn(high - table.outputBits, table.outputBits)))
      ._2
    new PlaPlan(pla, columns)
