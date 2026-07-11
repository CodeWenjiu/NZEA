package nzea_fpga

import nzea_config.{CacheConfig, SynthPlatform}
import nzea_core.config.CoreConfig
import nzea_tile.TileConfig

/** FPGA common defaults. All FPGA targets set sim=false, platform=Fpga. */
class FpgaConfig(
    val clockHz: Int = 100_000_000,
    val cache: Option[CacheConfig] = None,
    val perSlaveOutstanding: Int = 1,
    val core: CoreConfig = CoreConfig()
) {

  def tile: TileConfig = TileConfig(
    sim = false,
    synthPlatform = SynthPlatform.Fpga,
    clockHz = clockHz,
    cache = cache,
    perSlaveOutstanding = perSlaveOutstanding,
    core = core
  )

}
