package nzea_config

/** FU config: describes whether each FU needs PRF write, bypass, Rob access, etc.
  * Used for config-driven parameterized architecture: port count derived from config, top-level uses foreach for wiring.
  */
case class FuConfig(
  name: String,
  hasPrfWrite: Boolean = true,
  hasBypass: Boolean = true,
  hasRobAccess: Boolean = true
)

object FuConfig {
  /** PRF write port list derived from ISA config. Order: ALU, BRU, SYSU, [MUL, DIV], MemUnit.
    * Ports with hasBypass=true participate in operand bypass.
    */
  def prfWritePorts(implicit config: NzeaConfig): Seq[FuConfig] = {
    val exuBypass = Seq(
      FuConfig("ALU", hasPrfWrite = true, hasBypass = true),
      FuConfig("BRU", hasPrfWrite = true, hasBypass = true),
      FuConfig("SYSU", hasPrfWrite = true, hasBypass = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(
        FuConfig("MUL", hasPrfWrite = true, hasBypass = true), 
        FuConfig("DIV", hasPrfWrite = true, hasBypass = false)
      )
    ).getOrElse(Seq.empty)
    // MemUnit (load) must have hasBypass=true so IQ's combinational bypass sees load write-back.
    // Otherwise IQ entry stays rs1_ready=false despite PRF ready -> deadlock (PRF-IQ mismatch).
    exuBypass :+ FuConfig("MemUnit", hasPrfWrite = true, hasBypass = false)
  }

  /** PRF write ports provided by the integer execution cluster (excludes MemUnit). Used for its `io.out` size. */
  def exuPrfWritePorts(implicit config: NzeaConfig): Seq[FuConfig] =
    prfWritePorts.filter(_.name != "MemUnit")

  /** Number of PRF write ports the integer execution cluster provides (excludes MemUnit). */
  def numExuPrfWritePorts(implicit config: NzeaConfig): Int =
    exuPrfWritePorts.size

  /** Rob access port list derived from ISA config. Order: ALU, BRU, SYSU, [MUL, DIV], AGU.
    * 4 ports when hasM is false, 6 when hasM is true. */
  def robAccessPorts(implicit config: NzeaConfig): Seq[FuConfig] = {
    Seq(
      FuConfig("ALU", hasRobAccess = true),
      FuConfig("BRU", hasRobAccess = true),
      FuConfig("SYSU", hasRobAccess = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(FuConfig("MUL", hasRobAccess = true), FuConfig("DIV", hasRobAccess = true))
    ).getOrElse(Seq.empty) :+ FuConfig("AGU", hasRobAccess = true)
  }

  /** Total number of PRF write ports. */
  def numPrfWritePorts(implicit config: NzeaConfig): Int =
    prfWritePorts.size

  /** Total number of Rob access ports. */
  def numRobAccessPorts(implicit config: NzeaConfig): Int =
    robAccessPorts.size

  /** Issue port list: order ALU, BRU, AGU, [MUL, DIV], SYSU. Port index = hardware Vec index.
    * Each port supports one FuType. Used for mask-based routing in ISU. */
  def issuePorts(implicit config: NzeaConfig): Seq[FuConfig] = {
    Seq(
      FuConfig("ALU", hasRobAccess = true),
      FuConfig("BRU", hasRobAccess = true),
      FuConfig("AGU", hasRobAccess = true)
    ) ++ Option.when(config.isaConfig.hasM)(
      Seq(FuConfig("MUL", hasRobAccess = true), FuConfig("DIV", hasRobAccess = true))
    ).getOrElse(Seq.empty) :+ FuConfig("SYSU", hasRobAccess = true)
  }

  def numIssuePorts(implicit config: NzeaConfig): Int = issuePorts.size
}
