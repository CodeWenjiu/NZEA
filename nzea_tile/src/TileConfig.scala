package nzea_tile

import nzea_config.{CacheConfig, SynthPlatform}
import nzea_core.config.CoreConfig

/** Tile-level hardware generation parameters. */
case class TileConfig(
    synthPlatform: SynthPlatform = SynthPlatform.Yosys,
    clockHz: Int = 1_000_000_000,
    core: CoreConfig = CoreConfig(),
    cache: Option[CacheConfig] = Some(CacheConfig()),
    perSlaveOutstanding: Int = 8
)
