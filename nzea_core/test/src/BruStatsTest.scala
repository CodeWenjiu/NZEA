package nzea_core

import circt.stage.ChiselStage
import nzea_core.backend.integer.BRUStage0
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import org.scalatest.freespec.AnyFreeSpec

/** BRU stats counters: instantiated as a StatsRegs black box iff sim=true. */
class BruStatsTest extends AnyFreeSpec {

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
