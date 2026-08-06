package nzea_core

import circt.stage.ChiselStage
import nzea_core.backend.integer.BRUStage0
import nzea_core.config.{BpuConfig, CoreConfig}
import org.scalatest.freespec.AnyFreeSpec

/** BRU stats counters: instantiated as a StatsRegs black box iff sim=true. */
class BruStatsTest extends AnyFreeSpec {

  private def chirrtl(sim: Boolean): String = {
    implicit val config: CoreConfig = CoreConfig(sim = sim, bpu = BpuConfig(64, 16, Some(8)))
    ChiselStage.emitCHIRRTL(new BRUStage0(4, 6))
  }

  "BRUStage0 instantiates BpStats (StatsRegs) when sim=true" in {
    val c = chirrtl(sim = true)
    assert(c.contains("inst stats of BpStats"), s"StatsRegs instance missing:\n$c")
  }

  "BRUStage0 has no stats instance when sim=false" in {
    val c = chirrtl(sim = false)
    assert(!c.contains("BpStats") && !c.contains("stat_"), s"unexpected stats in synth:\n$c")
  }

}
