package nzea_sim

import circt.stage.ChiselStage
import nzea_config.{CacheConfig, FpgaBoard, SynthPlatform}
import nzea_tile.TileConfig
import nzea_core.config.CoreConfig
import nzea_tile.TileElaborate
import nzea_fpga.FpgaElaborate

object SimElaborate {

  def main(args: Array[String]): Unit = {
    require(args.length >= 3, "Usage: mill nzea_sim.run <target> <platform> <isa> [hex]")
    val target = args(0)
    val platform = args(1)
    val isa = args(2)
    val simOut = s"build/sim/$target/$platform/$isa/hw"

    implicit val coreConfig: CoreConfig = CoreConfig(isa = isa)

    // Step 1: generate DUT RTL
    target match {
      case "tile" =>
        val plat = SynthPlatform
          .fromString(platform)
          .getOrElse(
            throw new IllegalArgumentException(s"Unknown platform: $platform")
          )
        TileElaborate.elaborate(
          cfg = TileConfig(
            sim = false,
            synthPlatform = plat.segment,
            clockHz = 100_000_000,
            cache = None,
            perSlaveOutstanding = 1
          ),
          outDir = simOut
        )
      case "fpga" =>
        FpgaElaborate.elaborate(
          board = FpgaBoard
            .fromString(platform)
            .getOrElse(
              throw new IllegalArgumentException(s"Unknown board: $platform")
            ),
          outDir = simOut,
          clockHz = 100_000_000,
          firtoolOpts = SynthPlatform.Yosys.firtoolOpts(sim = false)
        )
      case other =>
        throw new IllegalArgumentException(s"Unknown target: $other (expected tile | fpga)")
    }

    // Step 2: generate Chisel testbench modules
    val firtoolBase =
      Array("--lowering-options=disallowLocalVariables", "-disable-all-randomization")
    target match {
      case "fpga" =>
        println(s"Generating FPGA sim testbench")
        ChiselStage.emitSystemVerilogFile(
          new TangNano20kSimTB,
          args = Array("--target-dir", simOut),
          firtoolOpts = firtoolBase :+ "-strip-debug-info"
        )
        TangNano20kSimTB.emitWrapper(simOut)
      case "tile" =>
        println(s"Generating tile sim modules: UartRxDisplay + CommitTracker")
        ChiselStage.emitSystemVerilogFile(
          new UartRxDisplay(baudDiv = 100_000_000 / 115200),
          args = Array("--target-dir", simOut),
          firtoolOpts = firtoolBase
        )
        ChiselStage.emitSystemVerilogFile(
          new CommitTracker(maxCycles = 50000000),
          args = Array("--target-dir", simOut),
          firtoolOpts = firtoolBase
        )
      case _ =>
    }
  }

}
