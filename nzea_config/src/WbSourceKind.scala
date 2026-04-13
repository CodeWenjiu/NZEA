package nzea_config

/** PRF write-back source kind.
  * Distinguishes execution-unit sources from MemUnit, which is not an issue-port FU.
  */
sealed trait WbSourceKind extends Product with Serializable

object WbSourceKind {
  final case class Exu(kind: FuKind) extends WbSourceKind
  case object MemUnit extends WbSourceKind
}
