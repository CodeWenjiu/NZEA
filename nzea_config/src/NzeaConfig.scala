package nzea_config

import mainargs.arg

case class NzeaConfig(
  @arg(doc = "Whether to enable Debug port") debug: Boolean = false,
  @arg(doc = "Verilog output directory (overrides default build/<target>/<platform>/<isa>/<sim|sta> when set)") outDir: Option[String] = None,
  @arg(doc = "Elaboration hierarchy: core (Top) or tile (NzeaTile)") target: ElaborationTarget = ElaborationTarget.Core,
  @arg(doc = "Backend platform segment (e.g. yosys)") synthPlatform: String = "yosys",
  @arg(doc = "If true, emit simulation RTL (DPI bridges); if false, emit synthesizable top-level IO") sim: Boolean = true,
  @arg(doc = "ISA string, e.g. riscv32i or riscv32im_zve32x_zvl128b (underscore-named extensions; order after `_` ignored)") isa: String = "riscv32i",
  @arg(doc = "Default PC (reset value)") defaultPc: Long = 0x8000_0000L,
  @arg(doc = "Rob depth (number of in-flight entries)") robDepth: Int = 16,
  @arg(doc = "Integer issue queue depth (entries between ISU and execution cluster)") issueQueueDepth: Int = 4,
  @arg(doc = "Physical register file depth (for rename)") prfDepth: Int = 64,
  @arg(doc = "Vector register width in bits when ISA has no zvl*N*b token (fallback VLEN)") vlen: Int = 128,
  @arg(doc = "Physical vector register file depth / PVR capacity (rename targets)") vrfDepth: Int = 64,
  @arg(doc = "Vector issue queue depth (RVV)") viqDepth: Int = 8,
  @arg(doc = "PHT size (power of 2)") phtSize: Int = 64,
  @arg(doc = "BTB size (power of 2)") btbSize: Int = 16
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Yosys)

  /** `sim` or `sta` under `build/<target>/<platform>/<isa>/`. */
  val rtlFlowSegment: String = if (sim) "sim" else "sta"

  /** firtool options from [[platform]] for current [[sim]] mode. */
  val firtoolOpts: Array[String] = platform.firtoolOpts(sim)

  /** Default and override-aware RTL output directory. */
  val effectiveOutDir: String =
    outDir.getOrElse(s"build/${target.segment}/${platform.segment}/${isa}/${rtlFlowSegment}")
  val prfAddrWidth: Int = Iterator.from(0).find(i => (1 << i) >= prfDepth).getOrElse(6)
  /** LS_Queue depth (for MemUnit); typically robDepth/2. */
  val effectiveLsBufferDepth: Int = (robDepth / 2).max(1)
  val lsqIdWidth: Int = Iterator.from(0).find(i => (1 << i) >= effectiveLsBufferDepth).getOrElse(1)
  val iqDepth: Int = issueQueueDepth.max(1)
  val iqIdWidth: Int = Iterator.from(0).find(i => (1 << i) >= iqDepth).getOrElse(1)
  /** PVR address width (vector rename target). */
  val pvrAddrWidth: Int = Iterator.from(0).find(i => (1 << i) >= vrfDepth).getOrElse(5)
  val viqDepthActual: Int = viqDepth.max(1)
  val viqIdWidth: Int     = Iterator.from(0).find(i => (1 << i) >= viqDepthActual).getOrElse(1)
  /** Parsed ISA config for Chisel to match extensions (e.g. isaConfig.hasM). */
  val isaConfig: IsaConfig = IsaConfig.parseOrThrow(isa)
  /** Data/address width derived from ISA (e.g. riscv32 -> 32, riscv64 -> 64). */
  val width: Int = isaConfig.xlen
  /** Effective VLEN: `zvl{N}b` in ISA if present, else `vlen`. */
  val effectiveVlen: Int = isaConfig.zvlBits.getOrElse(vlen)
}
