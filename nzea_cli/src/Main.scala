package nzea_cli

import mainargs.ParserForClass
import nzea_config.{ElaborationTarget}
import nzea_tile.TileConfig
import nzea_core.CoreElaborate
import nzea_core.config.CoreConfig
import nzea_tile.TileElaborate
import nzea_tile.platform.yosys.AddressMap
import nzea_fpga.FpgaElaborate

object Main {

  def main(args: Array[String]): Unit = {
    val cliArgs = ParserForClass[CliArgs].constructOrExit(args.toIndexedSeq)
    val config: TileConfig = cliArgs.toConfig
    implicit val coreConfig: CoreConfig = config.core
    config.target match {
      case ElaborationTarget.Tile =>
        TileElaborate.elaborate(
          cfg = config,
          outDir = config.effectiveOutDir
        )
      case ElaborationTarget.Core =>
        val mmioRanges = AddressMap.ranges.collect {
          case r if r.base != BigInt("80000000", 16) => (r.base, r.size)
        }
        CoreElaborate.elaborate(
          sim = config.sim,
          outDir = config.effectiveOutDir,
          firtoolOpts = config.firtoolOpts,
          mmioRanges = mmioRanges
        )
      case ElaborationTarget.Fpga =>
        FpgaElaborate.elaborate(
          board = config.fpgaBoard_,
          outDir = config.effectiveOutDir,
          clockHz = config.clockHz,
          firtoolOpts = config.platform.firtoolOpts(sim = false) // FPGA always synthesis
        )
    }
  }

}
