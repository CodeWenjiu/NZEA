package nzea_config

case class NzeaConfig(
  debug: Boolean = false,
  outDir: Option[String] = None,
  target: ElaborationTarget = ElaborationTarget.Core,
  synthPlatform: String = "yosys",
  sim: Boolean = true,
  core: CoreConfig = CoreConfig()
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Yosys)

  /** `sim` or `sta` under `build/<target>/<platform>/<isa>/`. */
  val rtlFlowSegment: String = if (sim) "sim" else "sta"

  /** firtool options from [[platform]] for current [[sim]] mode. */
  val firtoolOpts: Array[String] = platform.firtoolOpts(sim)

  /** Default and override-aware RTL output directory. */
  val effectiveOutDir: String =
    outDir.getOrElse(s"build/${target.segment}/${platform.segment}/${core.isa}/${rtlFlowSegment}")
}
