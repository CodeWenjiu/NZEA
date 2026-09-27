package nzea_rtl.stdlib

import utest.*

/** A toy configuration: the type the example's `exists` is asked about. A real one is `IsaConfig`; the point is that
  * only the union mentions it, so nothing else in the example knows the flag exists.
  */
case class ToyIsa(hasMul: Boolean)

/** The example's payload, so this needs no project code. Three codes, two bits. */
enum ToyOp extends Encoding.Sequential:
  case Add
  case Mul
  case Lsu

object ToyOp:
  val width: Int = Encoding.width(values)

/** The example, in full: a union whose one optional case states its own existence.
  *
  * An enum case cannot carry a body in Scala 3, so the cases answer `exists` from one exhaustive match in the union's
  * own declaration: a case that is not answered here does not go unnoticed — the compiler names the pattern it would
  * fail on, and building the set asks every declared case, so an unanswered one is a `MatchError` rather than a silent
  * hole. Everything else follows: the tag codes, which of them exist, and how wide the field is. Note what is *not*
  * here: a width, an `if hasMul`, a case list.
  */
enum ToyUnit(val childWidth: Int) extends Encoding.Union:
  case Add(payload: ToyOp) extends ToyUnit(ToyOp.width)
  case Mul(payload: ToyOp) extends ToyUnit(ToyOp.width)
  case Lsu(payload: ToyOp) extends ToyUnit(ToyOp.width)

  def exists(isa: ToyIsa): Boolean = this match
    case Add(_) | Lsu(_) => true
    case Mul(_)          => isa.hasMul

object ToyUnit:
  val add = Add(ToyOp.Add)
  val mul = Mul(ToyOp.Mul)
  val lsu = Lsu(ToyOp.Lsu)

  /** The one place the configuration is read. The call passes the cases in declaration order; `of` sorts by ordinal
    * anyway, so a tag is the case's declaration index among the ones that exist.
    */
  def set(isa: ToyIsa): Encoding.UnionSet[ToyUnit] =
    Encoding.UnionSet.of(Seq(add, mul, lsu))(_.exists(isa))

object UnionSetTest extends TestSuite:

  private def report(isa: ToyIsa): String =
    val ops = ToyUnit.set(isa)
    Seq(
      s"cases        = ${ops.cases.map(_.productPrefix).mkString(", ")}",
      s"payloadWidth = ${ops.payloadWidth}",
      s"tagWidth     = ${ops.tagWidth}",
      s"width        = ${ops.width}",
      s"lit(Mul)     = ${if ops.contains(ToyUnit.mul) then ops.lit(ToyUnit.mul).toString else "-"}",
      s"lit(Lsu)     = ${ops.lit(ToyUnit.lsu)}"
    ).mkString("\n  ")

  val tests = Tests:

    test("the example, both configurations"):
      println(s"ToyIsa(hasMul = false):\n  ${report(ToyIsa(hasMul = false))}")
      println(s"ToyIsa(hasMul = true):\n  ${report(ToyIsa(hasMul = true))}")

    test("the cases are the declared ones that exist, in declaration order"):
      assert(ToyUnit.set(ToyIsa(hasMul = true)).cases == Seq(ToyUnit.add, ToyUnit.mul, ToyUnit.lsu))
      assert(ToyUnit.set(ToyIsa(hasMul = false)).cases == Seq(ToyUnit.add, ToyUnit.lsu))

    test("an absent case takes its tag code with it, and the field narrows"):
      val full = ToyUnit.set(ToyIsa(hasMul = true))
      val lean = ToyUnit.set(ToyIsa(hasMul = false))
      assert(full.payloadWidth == ToyOp.width && lean.payloadWidth == ToyOp.width)
      assert(full.tagWidth == 2 && lean.tagWidth == 1)
      assert(full.width == ToyOp.width + 2 && lean.width == ToyOp.width + 1)
      assert(full.tagOf(ToyUnit.mul) == 1 && !lean.contains(ToyUnit.mul))
      // Lsu closes up onto Mul's old code instead of leaving a hole: it is the third case in one set, the second in the
      // other, and that difference is the tag column, nothing else.
      assert(full.lit(ToyUnit.lsu) == (ToyOp.Lsu.lit | (2 << full.payloadWidth)))
      assert(lean.lit(ToyUnit.lsu) == (ToyOp.Lsu.lit | (1 << lean.payloadWidth)))

    test("a case is asked about by variant, not by the payload it happens to carry"):
      val full = ToyUnit.set(ToyIsa(hasMul = true))
      assert(full.contains(ToyUnit.Mul(ToyOp.Add)))
      assert(full.tagOf(ToyUnit.Mul(ToyOp.Add)) == full.tagOf(ToyUnit.mul))

    test("a row built from an absent case is not one this configuration can issue"):
      val rows = Seq("add" -> ToyUnit.add, "mul" -> ToyUnit.mul, "lsu" -> ToyUnit.lsu)
      def live(isa: ToyIsa): Seq[String] =
        val ops = ToyUnit.set(isa)
        rows.collect { case (name, unit) if ops.contains(unit) => name }
      assert(live(ToyIsa(hasMul = true)) == Seq("add", "mul", "lsu"))
      assert(live(ToyIsa(hasMul = false)) == Seq("add", "lsu"))
