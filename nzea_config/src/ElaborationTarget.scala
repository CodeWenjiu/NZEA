package nzea_config

import mainargs.TokensReader

/** RTL elaboration root: core-only `Top` or SoC-style `NzeaTile`. */
sealed abstract class ElaborationTarget(val segment: String) extends Product with Serializable

object ElaborationTarget {
  case object Core extends ElaborationTarget("core")
  case object Tile extends ElaborationTarget("tile")

  def values: Seq[ElaborationTarget] = Seq(Core, Tile)

  def fromString(s: String): Either[String, ElaborationTarget] =
    s.toLowerCase match {
      case "core" => Right(Core)
      case "tile" => Right(Tile)
      case other  => Left(s"Invalid target '$other' (expected core or tile)")
    }

  implicit object TokensRead extends TokensReader.Simple[ElaborationTarget] {
    def shortName = "core|tile"
    def read(strs: Seq[String]): Either[String, ElaborationTarget] =
      strs.lastOption match {
        case None    => Left("target requires a value (core or tile)")
        case Some(v) => fromString(v)
      }
  }
}
