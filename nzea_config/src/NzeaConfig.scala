package nzea_config

import nzea_core.config.CoreConfig

case class NzeaConfig(
  debug: Boolean = false,
  outDir: Option[String] = None,
  target: ElaborationTarget = ElaborationTarget.Core,
  synthPlatform: String = "yosys",
  sim: Boolean = true,
  core: CoreConfig = CoreConfig()
) {
  val platform: SynthPlatform = SynthPlatform.fromString(synthPlatform).getOrElse(SynthPlatform.Yosys)

  /** `dpi` or `hw` under `build/<target>/<platform>/<isa>/`. */
  val rtlFlowSegment: String = if (sim) "dpi" else "hw"

  /** firtool options from [[platform]] for current [[sim]] mode. */
  val firtoolOpts: Array[String] = platform.firtoolOpts(sim)

  /** Default and override-aware RTL output directory. */
  val effectiveOutDir: String =
    outDir.getOrElse(s"build/${target.segment}/${platform.segment}/${core.isa}/${rtlFlowSegment}")
}
