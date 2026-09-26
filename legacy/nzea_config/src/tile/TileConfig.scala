package nzea_config.tile

import nzea_config.PlatformConfig
import nzea_config.SynthPlatform
import nzea_config.core.CacheConfig

/** Tile-level hardware generation parameters.
  *
  * No defaults and no `core` field (rule 6 + single-source goal): the core configuration travels exclusively through
  * the implicit `CoreConfig` channel, so every flow sees exactly the values its entry point assembled. Platform-
  * specific knobs live inside [[PlatformConfig]] variants, not in this shared record.
  */
case class TileConfig(
    platform: PlatformConfig,
    clockHz: Int,
    cache: Option[CacheConfig],
    perSlaveOutstanding: Int
) {

  /** Synthesis backend of the selected platform (dispatch/firtool convenience). */
  def synthPlatform: SynthPlatform = platform.synthPlatform
}
