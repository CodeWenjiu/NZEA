package nzea_config

/** Strongly-typed issue/execution FU kind used by static port/topology config.
  * MemUnit is intentionally excluded because it is not an issue-port FU.
  */
sealed trait FuKind extends Product with Serializable

object FuKind {
  case object Alu extends FuKind
  case object Bru extends FuKind
  case object Agu extends FuKind
  case object Mul extends FuKind
  case object Div extends FuKind
  case object Sysu extends FuKind
  case object Nnu extends FuKind
}
