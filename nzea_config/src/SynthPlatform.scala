package nzea_config

/** Synthesis platform: determines output dir and firtool options. */
sealed trait SynthPlatform {
  def outDir: String
  def firtoolOpts: Array[String]
}

object SynthPlatform {
  /** Default: RTL for simulation/verification. */
  case object Rtl extends SynthPlatform {
    override def outDir: String = "build/rtl"
    override def firtoolOpts: Array[String] = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  }

  /** Yosys: disallowLocalVariables, disallowPackedArrays for Yosys compatibility. */
  case object Yosys extends SynthPlatform {
    override def outDir: String = "build/yosys"
    override def firtoolOpts: Array[String] = Array(
      "--lowering-options=locationInfoStyle=wrapInAtSquareBracket,disallowLocalVariables,disallowPackedArrays"
    )
  }

  def fromString(s: String): Option[SynthPlatform] = s.toLowerCase match {
    case "rtl" | "default" | "" => Some(Rtl)
    case "yosys"                 => Some(Yosys)
    case _                       => None
  }
}
