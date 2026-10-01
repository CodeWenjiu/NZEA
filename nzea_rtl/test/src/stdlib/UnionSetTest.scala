package nzea_rtl.stdlib

import utest.*

/** A toy configuration: the type the example's `exists` is asked about, for both the union's cases and its child op
  * set. A real one is `IsaConfig`; the point is that only the declarations mention it.
  */
case class ToyIsa(mul: Boolean, wide: Boolean)

/** The example's child encoding, settable the same way: a base member set plus one a wider configuration adds. The
  * added member is declared last, so the base members keep their codes and the field only widens.
  */
enum ToyOp extends Encoding.OneHot:
    case Add
    case Lsu
    case Wide

    def exists(isa: ToyIsa): Boolean = this match
        case Add | Lsu => true
        case Wide      => isa.wide

object ToyOp:
    private val all = Seq(Add, Lsu, Wide)

    def set(isa: ToyIsa): OneHotSet[ToyOp] = OneHotSet.of(all)(_.exists(isa))

/** The example, in full: a union whose one optional case comes and goes with the configuration, and whose payload
  * column is as wide as the child op set that configuration has — both answer with the same `ToyIsa`.
  *
  * An enum case cannot carry a body in Scala 3, so the answers are two exhaustive matches in the union's own
  * declaration: a case that is not answered does not go unnoticed — the compiler names the pattern the match would fail
  * on, and building the set asks every declared case, so an unanswered one is a `MatchError` rather than a silent hole.
  */
enum ToyUnit extends Encoding.Union[ToyIsa]:
    case Add(payload: ToyOp) extends ToyUnit
    case Mul(payload: ToyOp) extends ToyUnit
    case Lsu(payload: ToyOp) extends ToyUnit

    def exists(isa: ToyIsa): Boolean = this match
        case Add(_) | Lsu(_) => true
        case Mul(_)          => isa.mul

    def childWidth(isa: ToyIsa): Int = ToyOp.set(isa).width

object ToyUnit:
    val add = Add(ToyOp.Add)
    val mul = Mul(ToyOp.Add)
    val lsu = Lsu(ToyOp.Lsu)

    /** The one place the configuration is read. The call passes the cases in declaration order; `of` sorts by ordinal
      * anyway, so a tag is the case's declaration index among the ones that exist.
      */
    def set(isa: ToyIsa): Encoding.UnionSet[ToyUnit, ToyIsa] =
        Encoding.UnionSet.of(isa)(Seq(add, mul, lsu))(_.exists(isa))

object UnionSetTest extends TestSuite:

    private val lean = ToyIsa(mul = false, wide = false)
    private val full = ToyIsa(mul = true, wide = true)

    private def report(isa: ToyIsa): String =
        val ops = ToyUnit.set(isa)
        val child = ToyOp.set(isa)
        Seq(
            s"cases        = ${ops.cases.map(_.productPrefix).mkString(", ")}",
            s"child        = ${child.variants.mkString(", ")}",
            s"payloadWidth = ${ops.payloadWidth}",
            s"tagWidth     = ${ops.tagWidth}",
            s"width        = ${ops.width}",
            s"lit(Mul)     = ${if ops.contains(ToyUnit.mul) then ops.lit(ToyUnit.mul).toString else "-"}",
            s"lit(Lsu)     = ${ops.lit(ToyUnit.lsu)}"
        ).mkString("\n  ")

    val tests = Tests:

        test("the example, both configurations"):
            for isa <- Seq(lean, full) do println(s"ToyIsa(mul = ${isa.mul}, wide = ${isa.wide}):\n  ${report(isa)}")

        test("the cases are the declared ones that exist, in declaration order"):
            assert(ToyUnit.set(full).cases == Seq(ToyUnit.add, ToyUnit.mul, ToyUnit.lsu))
            assert(ToyUnit.set(lean).cases == Seq(ToyUnit.add, ToyUnit.lsu))

        test("an absent case takes its tag code with it, and the field narrows"):
            val full = ToyUnit.set(ToyIsa(mul = true, wide = false))
            val lean = ToyUnit.set(ToyIsa(mul = false, wide = false))
            assert(full.payloadWidth == ToyOp.set(ToyIsa(mul = false, wide = false)).width)
            assert(full.tagWidth == 2 && lean.tagWidth == 1)
            assert(full.width == full.payloadWidth + 2 && lean.width == lean.payloadWidth + 1)
            assert(full.tagOf(ToyUnit.mul) == 1 && !lean.contains(ToyUnit.mul))
            // Lsu closes up onto Mul's old code instead of leaving a hole: it is the third case in one set, the second in the
            // other, and that difference is the tag column, nothing else.
            assert(full.lit(ToyUnit.lsu) == (ToyOp.Lsu.lit | (2 << full.payloadWidth)))
            assert(lean.lit(ToyUnit.lsu) == (ToyOp.Lsu.lit | (1 << lean.payloadWidth)))

        test("the payload column follows the case's own child set"):
            val narrow = ToyUnit.set(ToyIsa(mul = false, wide = false))
            val wide = ToyUnit.set(ToyIsa(mul = false, wide = true))
            assert(ToyOp.set(ToyIsa(mul = false, wide = false)).variants == Seq(ToyOp.Add, ToyOp.Lsu))
            assert(ToyOp.set(ToyIsa(mul = false, wide = true)).variants == Seq(ToyOp.Add, ToyOp.Lsu, ToyOp.Wide))
            // The base members keep their codes; the added one takes the next bit, so the child field is one bit wider.
            assert(ToyOp.set(ToyIsa(mul = false, wide = true)).width == 3)
            assert(narrow.payloadWidth == 2 && wide.payloadWidth == 3)
            assert(wide.tagWidth == narrow.tagWidth && wide.width == narrow.width + 1)

        test("a case is asked about by variant, not by the payload it happens to carry"):
            val full = ToyUnit.set(ToyIsa(mul = true, wide = true))
            assert(full.contains(ToyUnit.Mul(ToyOp.Lsu)))
            assert(full.tagOf(ToyUnit.Mul(ToyOp.Lsu)) == full.tagOf(ToyUnit.mul))

        test("a row built from an absent case is not one this configuration can issue"):
            val rows = Seq("add" -> ToyUnit.add, "mul" -> ToyUnit.mul, "lsu" -> ToyUnit.lsu)
            def live(isa: ToyIsa): Seq[String] =
                val ops = ToyUnit.set(isa)
                rows.collect { case (name, unit) if ops.contains(unit) => name }
            assert(live(full) == Seq("add", "mul", "lsu"))
            assert(live(lean) == Seq("add", "lsu"))
