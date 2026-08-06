package nzea_tile

import nzea_config.{CacheConfig, SynthPlatform}
import nzea_core.config.{BpuConfig, CoreConfig}

/** Tile-level hardware generation parameters. */
case class TileConfig(
    synthPlatform: SynthPlatform = SynthPlatform.Yosys,
    clockHz: Int = 1_000_000_000,
    core: CoreConfig = CoreConfig(bpu = BpuConfig(phtSize = 64, btbSize = 16, rasDepth = Some(8))),
    cache: Option[CacheConfig] = Some(CacheConfig()),
    perSlaveOutstanding: Int = 8
)
