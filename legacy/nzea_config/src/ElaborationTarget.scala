package nzea_config

import mainargs.TokensReader

/** RTL elaboration root: core-only `Top`, SoC-style `NzeaTile`, or FPGA board wrapper. */
sealed abstract class ElaborationTarget(val segment: String) extends Product with Serializable

object ElaborationTarget {
  case object Core extends ElaborationTarget("core")
  case object Tile extends ElaborationTarget("tile")
  case object Fpga extends ElaborationTarget("fpga")

  def values: Seq[ElaborationTarget] = Seq(Core, Tile, Fpga)

  def fromString(s: String): Either[String, ElaborationTarget] =
    s.toLowerCase match {
      case "core" => Right(Core)
      case "tile" => Right(Tile)
      case "fpga" => Right(Fpga)
      case other  => Left(s"Invalid target '$other' (expected core, tile, or fpga)")
    }

  implicit object TokensRead extends TokensReader.Simple[ElaborationTarget] {
    def shortName = "core|tile|fpga"

    def read(strs: Seq[String]): Either[String, ElaborationTarget] =
      strs.lastOption match {
        case None    => Left("target requires a value (core, tile, or fpga)")
        case Some(v) => fromString(v)
      }

  }

}
