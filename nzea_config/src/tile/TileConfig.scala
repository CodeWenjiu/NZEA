package nzea_config.tile

import nzea_config.core.CacheConfig
import nzea_config.SynthPlatform

/** Tile-level hardware generation parameters.
  *
  * No defaults and no `core` field (rule 6 + single-source goal): the core configuration travels exclusively through
  * the implicit `CoreConfig` channel, so every flow sees exactly the values its entry point assembled.
  */
case class TileConfig(
    synthPlatform: SynthPlatform,
    clockHz: Int,
    cache: Option[CacheConfig],
    perSlaveOutstanding: Int
)
