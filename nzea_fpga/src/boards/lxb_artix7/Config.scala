package nzea_fpga.boards.lxb_artix7

import nzea_config.{CacheConfig, SynthPlatform}
import nzea_tile.TileConfig
import nzea_core.config.CoreConfig

/** LxbArtix7 FPGA board configuration. Wraps a [[TileConfig]] with board-specific overrides: simulation disabled, FPGA
  * platform, no cache, 100 MHz clock, 8 outstanding bus transactions.
  */
case class LxbArtix7Config() {

  val tile: TileConfig = TileConfig(
    sim = false,
    synthPlatform = SynthPlatform.Fpga.segment,
    clockHz = 100_000_000,
    cache = None,
    perSlaveOutstanding = 8
  )

}
