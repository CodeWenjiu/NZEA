package nzea_cli

import mainargs.ParserForClass
import nzea_config.ElaborationTarget
import nzea_core.CoreElaborate
import nzea_config.core.CoreConfig
import nzea_tile.TileElaborate
import nzea_tile.platform.yosys.AddressMap
import nzea_fpga.FpgaElaborate

object Main {

  def main(args: Array[String]): Unit = {
    val cliArgs = ParserForClass[CliArgs].constructOrExit(args.toIndexedSeq)
    cliArgs.target match {
      case ElaborationTarget.Tile =>
        implicit val coreConfig: CoreConfig = cliArgs.coreConfig
        TileElaborate.elaborate(
          cfg = cliArgs.tileConfig,
          outDir = cliArgs.effectiveOutDir
        )
      case ElaborationTarget.Core =>
        val mmioRanges = AddressMap.ranges.collect {
          case r if r.base != BigInt("80000000", 16) => (r.base, r.size)
        }
        implicit val coreConfig: CoreConfig = cliArgs.coreConfig
        CoreElaborate.elaborate(
          outDir = cliArgs.effectiveOutDir,
          firtoolOpts = cliArgs.firtoolOpts,
          mmioRanges = mmioRanges
        )
      case ElaborationTarget.Fpga =>
        // FPGA builds never instantiate simulation-only logic, regardless of the CLI --sim flag.
        implicit val coreConfig: CoreConfig = cliArgs.coreConfig.copy(sim = false)
        FpgaElaborate.elaborate(
          board = cliArgs.fpgaBoard_,
          outDir = cliArgs.effectiveOutDir,
          clockHz = cliArgs.tileConfig.clockHz,
          firtoolOpts = cliArgs.tileConfig.synthPlatform.firtoolOpts(sim = false) // FPGA always synthesis
        )
    }
  }

}
