package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(doc = "CPU core data width") width: Int = 32,
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory (overrides platform default when set)") outDir: Option[String] = None,
  @arg(doc = "Platform: sim (default, Core+DPI), yosys (Core with exposed IO)") synthPlatform: String = "sim",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L,
  @arg(doc = "Rob depth (number of in-flight entries)") robDepth: Int = 4
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Sim)
  val effectiveOutDir: String = outDir.getOrElse(platform.outDir)
}
