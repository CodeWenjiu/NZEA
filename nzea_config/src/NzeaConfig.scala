package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(short = 'w', doc = "CPU core data width") width: Int = 32,
  @arg(short = 'd', doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(short = 'o', doc = "Verilog output directory") outDir: String = "build/nzea"
)
