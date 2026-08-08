package nzea_fpga.boards.lxb_artix7

import nzea_config.tile.TileConfig
import nzea_config.fpga.FpgaConfig

/** LxbArtix7 FPGA board: overrides FPGA common defaults with board-specific clock and bus depth. */
case class LxbArtix7Config()
    extends FpgaConfig(
      clockHz = 100_000_000,
      cache = None,
      perSlaveOutstanding = 8
    )
