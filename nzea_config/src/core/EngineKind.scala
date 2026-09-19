package nzea_config.core

import mainargs.TokensReader

/** Core microarchitecture kind — an atomic engine type, not a boolean feature matrix (see goal/core-baseline.md).
  *
  * `InOrder`: single-flight dispatch (MinCore); `OoO`: rename + issue queue + ROB (Core). The choice selects the middle
  * engine; front-end / back-end contracts stay fixed.
  */
sealed abstract class EngineKind(val segment: String) extends Product with Serializable

object EngineKind {
  case object InOrder extends EngineKind("in-order")
  case object OoO extends EngineKind("ooo")

  def values: Seq[EngineKind] = Seq(InOrder, OoO)

  def fromString(s: String): Either[String, EngineKind] =
    s.toLowerCase match {
      case "in-order" | "inorder" => Right(InOrder)
      case "ooo"                  => Right(OoO)
      case other                  => Left(s"Invalid engine '$other' (expected in-order or ooo)")
    }

  implicit object TokensRead extends TokensReader.Simple[EngineKind] {
    def shortName = "in-order|ooo"

    def read(strs: Seq[String]): Either[String, EngineKind] =
      strs.lastOption match {
        case None    => Left("engine requires a value (in-order or ooo)")
        case Some(v) => fromString(v)
      }

  }

}
