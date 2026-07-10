package nzea_fpga.boards.lxb_artix7

import nzea_config.{CacheConfig, NzeaConfigBase}

/** LxbArtix7 FPGA board hardware generation parameters. Overrides [[NzeaConfigBase]] with board-specific defaults:
  * simulation disabled, FPGA platform, no cache, 8 outstanding bus transactions.
  *
  * @param clockHz
  *   target core clock frequency (Hz)
  */
case class LxbArtix7Config(clockHz: Int) extends NzeaConfigBase {
  val sim: Boolean = false
  val synthPlatform: String = "fpga"
  val cache: Option[CacheConfig] = None
  val perSlaveOutstanding: Int = 1
}
