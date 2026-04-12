package nzea_config

/** Synthesis / backend platform (directory segment under `build/<target>/<segment>/<isa>/<sim|sta>/`). */
sealed trait SynthPlatform {
  def segment: String
  /** CIRCT/firtool flags for this backend; [[sim]] matches [[NzeaConfig.sim]] (simulation vs synthesis RTL). */
  def firtoolOpts(sim: Boolean): Array[String]
}

object SynthPlatform {
  /** Yosys flow: DPI/sim SV vs exposed-IO netlist use different lowering. */
  case object Yosys extends SynthPlatform {
    override def segment: String = "yosys"

    private val simulationFirtoolOpts: Array[String] = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )

    private val synthesisFirtoolOpts: Array[String] = Array(
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays"
    )

    override def firtoolOpts(sim: Boolean): Array[String] =
      if (sim) simulationFirtoolOpts else synthesisFirtoolOpts
  }

  def fromString(s: String): Option[SynthPlatform] = s.toLowerCase match {
    case "yosys" => Some(Yosys)
    case _       => None
  }
}
