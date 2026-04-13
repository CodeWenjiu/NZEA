package nzea_config

/** FU config: describes whether each FU needs PRF write, bypass, Rob access, etc.
  * Used for config-driven parameterized architecture: port count derived from config, top-level uses foreach for wiring.
  */
case class FuConfig(
  kind: FuKind,
  hasPrfWrite: Boolean = true,
  hasBypass: Boolean = true,
  hasRobAccess: Boolean = true,
  /** Early wakeup hint latency from IQ read-stage dispatch to IQ select-stage observe point. */
  wakeupHintLatency: Option[Int] = None
)

case class WbSourceConfig(
  kind: WbSourceKind,
  hasBypass: Boolean = true
)

object FuConfig {
  /** PRF write source list derived from ISA config. Order: ALU, BRU, SYSU, [MUL, DIV], [NNU], MemUnit.
    * Ports with hasBypass=true participate in operand bypass.
    */
  def prfWritePorts(implicit config: CoreConfig): Seq[WbSourceConfig] = {
    val exuBypass = Seq(
      WbSourceConfig(WbSourceKind.Exu(FuKind.Alu), hasBypass = true),
      WbSourceConfig(WbSourceKind.Exu(FuKind.Bru), hasBypass = true),
      WbSourceConfig(WbSourceKind.Exu(FuKind.Sysu), hasBypass = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(
        WbSourceConfig(WbSourceKind.Exu(FuKind.Mul), hasBypass = true),
        WbSourceConfig(WbSourceKind.Exu(FuKind.Div), hasBypass = false)
      )
    ).getOrElse(Seq.empty) ++ Option.when(config.isaConfig.hasWjcus0)(
      Seq(WbSourceConfig(WbSourceKind.Exu(FuKind.Nnu), hasBypass = true))
    ).getOrElse(Seq.empty)
    // MemUnit is not an issue-port FU. Keep hasBypass=false so it does not participate in
    // IQ select-stage combinational bypass hit checks; readiness is updated via write-back.
    exuBypass :+ WbSourceConfig(WbSourceKind.MemUnit, hasBypass = false)
  }

  /** PRF write ports provided by the integer execution cluster (excludes MemUnit). Used for its `io.out` size. */
  def exuPrfWritePorts(implicit config: CoreConfig): Seq[FuKind] =
    prfWritePorts.collect { case WbSourceConfig(WbSourceKind.Exu(kind), _) => kind }

  /** Number of PRF write ports the integer execution cluster provides (excludes MemUnit). */
  def numExuPrfWritePorts(implicit config: CoreConfig): Int =
    exuPrfWritePorts.size

  /** Rob access port list derived from ISA config. Order: ALU, BRU, SYSU, [MUL, DIV], [NNU], AGU.
    * Port count depends on hasM / hasWjcus0. */
  def robAccessPorts(implicit config: CoreConfig): Seq[FuConfig] = {
    Seq(
      FuConfig(FuKind.Alu, hasRobAccess = true),
      FuConfig(FuKind.Bru, hasRobAccess = true),
      FuConfig(FuKind.Sysu, hasRobAccess = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(FuConfig(FuKind.Mul, hasRobAccess = true), FuConfig(FuKind.Div, hasRobAccess = true))
    ).getOrElse(Seq.empty) ++ Option.when(config.isaConfig.hasWjcus0)(
      Seq(FuConfig(FuKind.Nnu, hasRobAccess = true))
    ).getOrElse(Seq.empty) :+ FuConfig(FuKind.Agu, hasRobAccess = true)
  }

  /** Total number of PRF write ports. */
  def numPrfWritePorts(implicit config: CoreConfig): Int =
    prfWritePorts.size

  /** Total number of Rob access ports. */
  def numRobAccessPorts(implicit config: CoreConfig): Int =
    robAccessPorts.size

  /** Issue port list: order ALU, BRU, AGU, [MUL, DIV], [NNU], SYSU. Port index = hardware Vec index.
    * Each port supports one FuType. Used for mask-based routing in ISU. */
  def issuePorts(implicit config: CoreConfig): Seq[FuConfig] = {
    Seq(
      FuConfig(FuKind.Alu, hasRobAccess = true, wakeupHintLatency = Some(0)),
      FuConfig(FuKind.Bru, hasRobAccess = true),
      FuConfig(FuKind.Agu, hasRobAccess = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(
        FuConfig(FuKind.Mul, hasRobAccess = true, wakeupHintLatency = Some(2)),
        FuConfig(FuKind.Div, hasRobAccess = true)
      )
    ).getOrElse(Seq.empty) ++ Option.when(config.isaConfig.hasWjcus0)(
      Seq(FuConfig(FuKind.Nnu, hasRobAccess = true))
    ).getOrElse(Seq.empty) :+ FuConfig(FuKind.Sysu, hasRobAccess = true)
  }

  def numIssuePorts(implicit config: CoreConfig): Int = issuePorts.size

  /** Number of early wakeup hint channels produced by IQ read-stage. */
  def numWakeupHints(implicit config: CoreConfig): Int =
    issuePorts.count(_.wakeupHintLatency.nonEmpty)
}
