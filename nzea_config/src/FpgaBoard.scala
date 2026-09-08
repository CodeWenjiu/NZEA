package nzea_config

/** FPGA board target, determines the top-level wrapper and pin mappings.
  *
  * `extMemBuses` is the board's external-memory bus count (fpga-platform tile
  * shape): bus 0 is always the boot RAM, additional buses are extra memory
  * regions (e.g. lxb_artix7 bus 1 = board DDR3 via the MIG adapter).
  */
sealed abstract class FpgaBoard(val segment: String, val extMemBuses: Int) extends Product with Serializable

object FpgaBoard {
  case object LxbArtix7 extends FpgaBoard("lxb_artix7", extMemBuses = 2)
  case object TangNano20k extends FpgaBoard("tangnano20k", extMemBuses = 1)

  def values: Seq[FpgaBoard] = Seq(LxbArtix7, TangNano20k)

  def fromString(s: String): Either[String, FpgaBoard] = s.toLowerCase match {
    case "lxb_artix7"  => Right(LxbArtix7)
    case "tangnano20k" => Right(TangNano20k)
    case other         => Left(s"Invalid fpga-board '$other' (expected lxb_artix7 or tangnano20k)")
  }

}
