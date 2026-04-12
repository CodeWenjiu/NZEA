package nzea_config

/** Platform: simulation (Core+DPI) or synthesis (exposed IO). */
sealed trait SynthPlatform {
  /** Directory segment under `build/<target>/<segment>/<isa>`. */
  def segment: String
  def firtoolOpts: Array[String]
}

object SynthPlatform {
  /** Default: simulation. Core's bus connects to DPI-C bridges. */
  case object Sim extends SynthPlatform {
    override def segment: String = "sim"
    override def firtoolOpts: Array[String] = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  }

  /** Yosys synthesis. Core with ibus/dbus/commit exposed as top-level IO. */
  case object Yosys extends SynthPlatform {
    override def segment: String = "yosys"
    override def firtoolOpts: Array[String] = Array(
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays"
    )
  }

  def fromString(s: String): Option[SynthPlatform] = s.toLowerCase match {
    case "sim" | "rtl" | "default" | "" => Some(Sim)
    case "yosys"                        => Some(Yosys)
    case _                              => None
  }
}
