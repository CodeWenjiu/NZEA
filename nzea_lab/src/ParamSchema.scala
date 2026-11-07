package nzea_lab

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

/** What a parameter field holds: the kinds a command line can spell and `describe` can print. */
enum ParamKind:
    case Int, Str, Bool

/** One field of a generator's parameter case class, derived from its `Mirror`. */
final case class ParamSpec(name: String, kind: ParamKind, doc: String)

/** The parameter values a command line carries, before a generator turns them into its own case class. */
final case class ParamValues(values: Map[String, String]):

    def int(name: String): Int =
        values.getOrElse(name, throw new IllegalArgumentException(s"missing parameter '$name'")).toInt

    def str(name: String): String =
        values.getOrElse(name, throw new IllegalArgumentException(s"missing parameter '$name'"))

    def bool(name: String): Boolean =
        values.getOrElse(name, throw new IllegalArgumentException(s"missing parameter '$name'")).toBoolean

/** A generator's parameter fields, derived at the concrete case class. */
trait ParamSchema[A]:
    def specs: Seq[ParamSpec]

object ParamSchema:

    inline given derived[A <: Product](using m: Mirror.ProductOf[A]): ParamSchema[A] =
        val computed = specsOf[m.MirroredElemLabels, m.MirroredElemTypes]
        new ParamSchema[A]:
            def specs = computed

    private inline def specsOf[Labels <: Tuple, Types <: Tuple]: Seq[ParamSpec] =
        inline (erasedValue[Labels], erasedValue[Types]) match
            case (_: EmptyTuple, _: EmptyTuple) => Nil
            case (_: (label *: labels), _: (tpe *: types)) =>
                ParamSpec(summonInline[ValueOf[label]].value.toString, kindOf[tpe], "") +: specsOf[labels, types]

    private inline def kindOf[T]: ParamKind =
        inline erasedValue[T] match
            case _: Int     => ParamKind.Int
            case _: String  => ParamKind.Str
            case _: Boolean => ParamKind.Bool
            case _          => ParamKind.Str
