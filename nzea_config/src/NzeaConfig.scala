package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(doc = "CPU core data width") width: Int = 32,
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory") outDir: String = "build",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L
)
