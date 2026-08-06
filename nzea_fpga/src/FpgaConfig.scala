package nzea_fpga

import nzea_config.{CacheConfig, SynthPlatform}
import nzea_core.config.{BpuConfig, CoreConfig}
import nzea_tile.TileConfig

/** FPGA common defaults. All FPGA targets set sim=false, platform=Fpga. */
class FpgaConfig(
    val clockHz: Int = 100_000_000,
    val cache: Option[CacheConfig] = None,
    val perSlaveOutstanding: Int = 1,
    val core: CoreConfig = CoreConfig(bpu = BpuConfig(phtSize = 64, btbSize = 16, rasDepth = Some(8)))
) {

  def tile: TileConfig = TileConfig(
    synthPlatform = SynthPlatform.Fpga,
    clockHz = clockHz,
    cache = cache,
    perSlaveOutstanding = perSlaveOutstanding,
    // FPGA builds never instantiate simulation-only logic.
    core = core.copy(sim = false)
  )

}
