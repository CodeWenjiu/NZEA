package nzea_core

import circt.stage.ChiselStage
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import org.scalatest.freespec.AnyFreeSpec

/** Core execution stats (stat_cycle, stat_inst_commit): StatsRegs iff sim=true. */
class CoreStatsTest extends AnyFreeSpec {

  private def chirrtl(sim: Boolean): String = {
    implicit val config: CoreConfig = CoreConfig(
      isa = "riscv32i",
      defaultPc = 0x8000_0000L,
      robDepth = 16,
      issueQueueDepth = 4,
      prfDepth = 64,
      vlen = 128,
      vrfDepth = 64,
      viqDepth = 8,
      bpu = BpuConfig.typical,
      sim = sim
    )
    ChiselStage.emitCHIRRTL(new Core)
  }

  "Core instantiates CoreStats when sim=true" in {
    val c = chirrtl(sim = true)
    assert(c.contains("inst stats of CoreStats"), s"CoreStats instance missing:\n$c")
  }

  "Core has no stats instance when sim=false" in {
    val c = chirrtl(sim = false)
    assert(!c.contains("CoreStats"), s"unexpected stats in synth:\n$c")
  }

}
