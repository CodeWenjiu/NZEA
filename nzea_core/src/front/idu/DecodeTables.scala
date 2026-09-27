package nzea_core.front.idu

import me.jiuyang.decoder.{BitPat, BitSet, TruthTable}
import nzea_core.back.OpWindow
import nzea_core.back.int.IntOp
import nzea_core.isa.{Backend, Srcs}
import nzea_config.core.{CoreConfig, Isa}
import nzea_rtl.stdlib.Encoding

import scala.collection.immutable.SortedMap

/** The truth tables the decode PLA is built from: one per field of the decode output, in the order the output record
  * carries them.
  *
  * A table lists only the instructions that differ from its default — an unmatched instruction falls back to the
  * defaults below, which are the legacy decoder's (immediate shape I, no register write or read, zero window). A zero
  * window decodes to the int path's ALU with op 0 and no source, which writes nothing, so an unknown instruction is a
  * no-op.
  */
object DecodeTables:

  /** Names of the decode output fields; the PLA emits one field per table, in this order. */
  val BackendName = "backend"
  val OpName = "op"
  val SrcsName = "srcs"
  val ImmTypeName = "imm_type"
  val GprWrName = "gpr_wr"

  /** The default window: no unit selected (op 0), no source, int path — the no-op an unknown instruction decodes to. */
  private val NoWindowKey = "zero"

  def all(config: CoreConfig): Seq[TruthTable] =
    val ops = IntOp.set(config.isa)
    val insts = RiscvInsts.all(config)
    Seq(
      backend(insts),
      window(insts, ops, OpWindow.width(config)),
      srcs(insts),
      immType(insts),
      gprWr(insts)
    )

  private def backend(insts: Seq[RVInst]): TruthTable =
    TruthTable(
      BackendName,
      insts.filter(_.dispatch.backend != Backend.INT).map(i => i.pattern -> i.dispatch.backend.toString),
      Backend.INT.toString,
      encode(Backend.values.toSeq.map(b => b.toString -> b.lit), Backend.width)
    )

  /** The whole decode result of an instruction, packed by its backend against the units this configuration has. */
  private def window(insts: Seq[RVInst], ops: Encoding.UnionSet[IntOp, Isa], width: Int): TruthTable =
    TruthTable(
      OpName,
      insts.map(i => i.pattern -> i.dispatch.name),
      NoWindowKey,
      encode(insts.map(i => i.dispatch.name -> i.dispatch.window(ops)) :+ (NoWindowKey -> 0), width)
    )

  /** The operand form: which source feeds each of the uop's three operand slots. */
  private def srcs(insts: Seq[RVInst]): TruthTable =
    TruthTable(
      SrcsName,
      insts.flatMap(i => Option.when(i.dispatch.srcs != Srcs.NoOperand)(i.pattern -> i.dispatch.srcs.toString)),
      Srcs.NoOperand.toString,
      encode(Srcs.values.toSeq.map(s => s.toString -> s.lit), Srcs.width)
    )

  private def immType(insts: Seq[RVInst]): TruthTable =
    TruthTable(
      ImmTypeName,
      insts.flatMap(i => i.immType.map(t => i.pattern -> t.toString)),
      ImmType.I.toString,
      encode(ImmType.values.toSeq.map(t => t.toString -> t.lit), ImmType.width)
    )

  private def gprWr(insts: Seq[RVInst]): TruthTable = boolean(GprWrName, insts.filter(_.gprWr).map(_.pattern))

  /** A field that is true for the listed patterns and false otherwise: only the true side needs an entry. */
  private def boolean(name: String, patterns: Seq[BitSet]): TruthTable =
    TruthTable(
      name,
      patterns.map(_ -> "1"),
      "0",
      SortedMap("0" -> BitSet.n(1), "1" -> BitSet.y(1))
    )

  /** The encoding of a field: every key's bit pattern, all of them `width` bits wide. */
  private def encode(entries: Seq[(String, Int)], width: Int): SortedMap[String, BitPat] =
    val mask = (BigInt(1) << width) - 1
    SortedMap.from(entries.map((key, value) => key -> BitSet.bitpat(value, mask, width)))
