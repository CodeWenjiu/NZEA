package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory (overrides platform default when set)") outDir: Option[String] = None,
  @arg(doc = "Platform: sim (default, Core+DPI), yosys (Core with exposed IO)") synthPlatform: String = "sim",
  @arg(doc = "ISA for compilation (e.g. riscv32i)") isa: String = "riscv32i",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L,
  @arg(doc = "Rob depth (number of in-flight entries)") robDepth: Int = 16,
  @arg(doc = "Physical register file depth (for rename)") prfDepth: Int = 64,
  @arg(doc = "BHT size (power of 2)") bhtSize: Int = 64,
  @arg(doc = "BTB size (power of 2)") btbSize: Int = 16
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Sim)
  val effectiveOutDir: String = outDir.getOrElse(s"${platform.outDir}/${isa}")
  val prfAddrWidth: Int = Iterator.from(0).find(i => (1 << i) >= prfDepth).getOrElse(6)
  /** Parsed ISA config for Chisel to match extensions (e.g. isaConfig.hasM). */
  val isaConfig: IsaConfig = IsaConfig.parse(isa)
  /** Data/address width derived from ISA (e.g. riscv32 -> 32, riscv64 -> 64). */
  val width: Int = isaConfig.xlen
}
