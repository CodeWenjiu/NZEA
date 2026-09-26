package nzea_rtl.stdlib

import scala.collection.immutable.SortedMap

import me.jiuyang.decoder.{BitSet, PLA, TruthTable}
import nzea_rtl.PlaSim
import utest.*

/** The minimizer, checked the way the hardware reads it: a minimized PLA must report exactly what its tables say, for
  * every word of a small input space, and it must be smaller than the tables it came from.
  */
object QmcTest extends TestSuite:

  /** One field: `name`, its default's key, the entries (`pattern -> key`) and the encoding of every key. */
  private def field(
      name: String,
      default: String,
      entries: Seq[(String, String)],
      encoding: Seq[(String, String)]
  ): TruthTable =
    TruthTable(
      name,
      entries.map((pattern, key) => BitSet.bitset(pattern) -> key),
      default,
      SortedMap.from(encoding.map((key, bits) => key -> BitSet.bitpat(bits)))
    )

  /** A 5-bit space: one field whose default asserts a bit (the `00001` encoding), one whose default is zero, and one
    * entry that covers two patterns — the merge the decode tables rely on.
    */
  private val shape = field(
    "shape",
    "i",
    Seq("000??" -> "r", "001??" -> "s", "01?00" -> "u", "11?00" -> "j"),
    Seq("i" -> "00001", "r" -> "00010", "s" -> "00100", "u" -> "01000", "j" -> "10000")
  )

  private val flags = field(
    "flags",
    "none",
    Seq("000??" -> "wr", "001?0" -> "wr", "01?00" -> "none", "11?00" -> "wr"),
    Seq("none" -> "0", "wr" -> "1")
  )

  private val tables = Seq(shape, flags)
  private val width = 5
  private lazy val pla = Qmc(tables)

  private def words: Seq[BigInt] = (0 until (1 << width)).map(BigInt(_))

  val tests = Tests:
    test("the baked PLA has the tables' shape"):
      assert(pla.inputWidth == width)
      assert(pla.outputWidth == tables.map(_.outputBits).sum)
      assert(pla.table.nonEmpty)

    test("the minimized PLA decodes every word of the space exactly as the tables do"):
      for word <- words do assert(PlaSim.decode(pla, word) == PlaSim.reference(tables, word))

    test("minimizing shrinks the cover instead of restating the tables"):
      val entries = tables.map(_.table.length).sum
      assert(pla.table.length < entries)

    test("adjacent entries merge into one row"):
      // `000??`/`001??` and `01?00`/`11?00` each disagree on one literal: one cube afterwards, not four entries.
      val inputs = pla.table.map(_._1.toString)
      assert(inputs.contains("00???"))
      assert(inputs.contains("?1?00"))

    test("a cube that covers two entries never asserts a bit outside their onset"):
      // The `?1?00` merge covers `01?00` (no write) and `11?00` (write): only the latter's rows may raise the flag.
      val merge = pla.table.find(_._1.toString == "?1?00").get
      assert(!merge._2.mask.testBit(0))

    test("an empty table set is rejected"):
      assert(scala.util.Try(Qmc(Seq.empty)).isFailure)
