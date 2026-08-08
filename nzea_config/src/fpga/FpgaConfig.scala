package nzea_config.fpga

import nzea_config.core.CacheConfig
import nzea_config.SynthPlatform
import nzea_config.tile.TileConfig

/** FPGA common configuration. No defaults (rule 6): board configs pass explicit values. `sim=false` is enforced by the
  * elaboration entry point, not stored here.
  */
class FpgaConfig(
    val clockHz: Int,
    val cache: Option[CacheConfig],
    val perSlaveOutstanding: Int
) {

  def tile: TileConfig = TileConfig(
    synthPlatform = SynthPlatform.Fpga,
    clockHz = clockHz,
    cache = cache,
    perSlaveOutstanding = perSlaveOutstanding
  )

}
