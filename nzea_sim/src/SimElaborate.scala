package nzea_sim

import circt.stage.ChiselStage
import nzea_config.core.CacheConfig
import nzea_config.FpgaBoard
import nzea_config.SynthPlatform
import nzea_config.tile.TileConfig
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import nzea_tile.TileElaborate
import nzea_tile.platform.yosys
import nzea_fpga.FpgaElaborate

object SimElaborate {

  def main(args: Array[String]): Unit = {
    require(args.length >= 3, "Usage: mill nzea_sim.run <target> <platform> <isa> [hex]")
    val target = args(0)
    val platform = args(1)
    val isa = args(2)
    val simOut = s"build/sim/$target/$platform/$isa/hw"

    implicit val coreConfig: CoreConfig = CoreConfig(
      isa = isa,
      defaultPc = 0x8000_0000L,
      robDepth = 16,
      issueQueueDepth = 4,
      prfDepth = 64,
      vlen = 128,
      vrfDepth = 64,
      viqDepth = 8,
      bpu = BpuConfig.typical,
      sim = false
    )

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
            synthPlatform = plat,
            clockHz = 100_000_000,
            cache = None,
            perSlaveOutstanding = 1
          ),
          outDir = simOut
        )
        // Platform deliverables for the outer devices.
        plat match {
          case SynthPlatform.Yosys =>
            // Outer devices are our own RTL (RAM/UART/CLINT/finisher live on the board);
            // emit their standard stand-ins so RTL simulation can form the full system.
            yosys.DeviceModels.emit(simOut, clockHz = 100_000_000)
          case SynthPlatform.Fpga =>
          // Outer devices are board/platform-provided (SRAM adapter today; future Vivado
          // IP such as MIG wrapped as external devices). Their sim models come from the
          // vendor's official simulation files — never hand-written here.
        }
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
        println(s"Generating tile sim modules: CommitTracker")
        ChiselStage.emitSystemVerilogFile(
          new CommitTracker(maxCycles = 50000000),
          args = Array("--target-dir", simOut),
          firtoolOpts = firtoolBase
        )
      case _ =>
    }
  }

}
