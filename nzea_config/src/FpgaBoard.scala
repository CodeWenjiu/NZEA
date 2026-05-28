package nzea_config

/** FPGA board target, determines the top-level wrapper and pin mappings. */
sealed abstract class FpgaBoard(val segment: String) extends Product with Serializable

object FpgaBoard {
  case object LxbArtix7 extends FpgaBoard("lxb_artix7")
  case object TangNano20k extends FpgaBoard("tangnano20k")

  def values: Seq[FpgaBoard] = Seq(LxbArtix7, TangNano20k)

  def fromString(s: String): Either[String, FpgaBoard] = s.toLowerCase match {
    case "lxb_artix7"  => Right(LxbArtix7)
    case "tangnano20k" => Right(TangNano20k)
    case other         => Left(s"Invalid fpga-board '$other' (expected lxb_artix7 or tangnano20k)")
  }

}
