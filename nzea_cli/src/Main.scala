package nzea_cli

import mainargs.ParserForClass
import nzea_config.ElaborationTarget
import nzea_core.CoreElaborate
import nzea_core.config.CoreConfig
import nzea_tile.TileElaborate
import nzea_tile.platform.yosys.AddressMap
import nzea_fpga.FpgaElaborate

object Main {

  def main(args: Array[String]): Unit = {
    val cliArgs = ParserForClass[CliArgs].constructOrExit(args.toIndexedSeq)
    val cfg = cliArgs.tileConfig
    implicit val coreConfig: CoreConfig = cfg.core
    cliArgs.target match {
      case ElaborationTarget.Tile =>
        TileElaborate.elaborate(
          cfg = cfg,
          outDir = cliArgs.effectiveOutDir
        )
      case ElaborationTarget.Core =>
        val mmioRanges = AddressMap.ranges.collect {
          case r if r.base != BigInt("80000000", 16) => (r.base, r.size)
        }
        CoreElaborate.elaborate(
          outDir = cliArgs.effectiveOutDir,
          firtoolOpts = cliArgs.firtoolOpts,
          mmioRanges = mmioRanges
        )
      case ElaborationTarget.Fpga =>
        FpgaElaborate.elaborate(
          board = cliArgs.fpgaBoard_,
          outDir = cliArgs.effectiveOutDir,
          clockHz = cfg.clockHz,
          firtoolOpts = cfg.synthPlatform.firtoolOpts(sim = false) // FPGA always synthesis
        )
    }
  }

}
