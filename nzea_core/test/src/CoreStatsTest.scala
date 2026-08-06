package nzea_core

import circt.stage.ChiselStage
import nzea_core.config.{BpuConfig, CoreConfig}
import org.scalatest.freespec.AnyFreeSpec

/** Core execution stats (stat_cycle, stat_inst_commit): StatsRegs iff sim=true. */
class CoreStatsTest extends AnyFreeSpec {

  private def chirrtl(sim: Boolean): String = {
    implicit val config: CoreConfig = CoreConfig(sim = sim, bpu = BpuConfig(64, 16, Some(8)))
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
