package nzea_config

/** Core-specific configuration used by nzea_core modules. */
case class CoreConfig(
  isa: String = "riscv32i",
  defaultPc: Long = 0x8000_0000L,
  robDepth: Int = 16,
  issueQueueDepth: Int = 4,
  prfDepth: Int = 64,
  vlen: Int = 128,
  vrfDepth: Int = 64,
  viqDepth: Int = 8,
  phtSize: Int = 64,
  btbSize: Int = 16
) {
  val prfAddrWidth: Int = Iterator.from(0).find(i => (1 << i) >= prfDepth).getOrElse(6)
  /** LS_Queue depth (for MemUnit); typically robDepth/2. */
  val effectiveLsBufferDepth: Int = (robDepth / 2).max(1)
  val lsqIdWidth: Int             = Iterator.from(0).find(i => (1 << i) >= effectiveLsBufferDepth).getOrElse(1)
  val iqDepth: Int                = issueQueueDepth.max(1)
  val iqIdWidth: Int              = Iterator.from(0).find(i => (1 << i) >= iqDepth).getOrElse(1)
  /** PVR address width (vector rename target). */
  val pvrAddrWidth: Int  = Iterator.from(0).find(i => (1 << i) >= vrfDepth).getOrElse(5)
  val viqDepthActual: Int = viqDepth.max(1)
  val viqIdWidth: Int     = Iterator.from(0).find(i => (1 << i) >= viqDepthActual).getOrElse(1)
  /** Parsed ISA config for Chisel to match extensions (e.g. isaConfig.hasM). */
  val isaConfig: IsaConfig = IsaConfig.parseOrThrow(isa)
  /** Data/address width derived from ISA (e.g. riscv32 -> 32, riscv64 -> 64). */
  val width: Int = isaConfig.xlen
  /** Effective VLEN: `zvl{N}b` in ISA if present, else `vlen`. */
  val effectiveVlen: Int = isaConfig.zvlBits.getOrElse(vlen)
}
