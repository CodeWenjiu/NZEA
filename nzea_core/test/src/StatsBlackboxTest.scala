package nzea_core

import circt.stage.ChiselStage
import nzea_core.backend.integer.BRUStage1
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import org.scalatest.freespec.AnyFreeSpec

/** StatsRegs black-box instantiation tests: the sim-only stats counter is
  * emitted iff `sim=true`. Covers both the Core-level CoreStats and the
  * BRU-level BpStats.
  */
class StatsBlackboxTest extends AnyFreeSpec {

  private def config(sim: Boolean): CoreConfig = CoreConfig(
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

  "Core instantiates CoreStats when sim=true" in {
    implicit val c: CoreConfig = config(sim = true)
    val chirrtl = ChiselStage.emitCHIRRTL(new Core)
    assert(chirrtl.contains("inst stats of CoreStats"), s"CoreStats instance missing:\n$chirrtl")
  }

  "Core has no stats instance when sim=false" in {
    implicit val c: CoreConfig = config(sim = false)
    val chirrtl = ChiselStage.emitCHIRRTL(new Core)
    assert(!chirrtl.contains("CoreStats"), s"unexpected stats in synth:\n$chirrtl")
  }

  "BRUStage1 instantiates BpStats (StatsRegs) when sim=true" in {
    implicit val c: CoreConfig = config(sim = true)
    val chirrtl = ChiselStage.emitCHIRRTL(new BRUStage1(4, 6))
    assert(chirrtl.contains("inst stats of BpStats"), s"StatsRegs instance missing:\n$chirrtl")
  }

  "BRUStage1 has no stats instance when sim=false" in {
    implicit val c: CoreConfig = config(sim = false)
    val chirrtl = ChiselStage.emitCHIRRTL(new BRUStage1(4, 6))
    assert(!chirrtl.contains("BpStats") && !chirrtl.contains("stat_"), s"unexpected stats in synth:\n$chirrtl")
  }

}
