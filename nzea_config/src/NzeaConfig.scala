package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(doc = "CPU core data width") width: Int = 32,
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory (overrides platform default when set)") outDir: Option[String] = None,
  @arg(doc = "Synthesis platform: rtl (default), yosys") synthPlatform: String = "rtl",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Rtl)
  val effectiveOutDir: String = outDir.getOrElse(platform.outDir)
}
