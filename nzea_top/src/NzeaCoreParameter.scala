package nzea_top

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope

/** The core level top's parameter: the level's configuration tree plus the knobs that shape the dump.
  *
  * The design is the tree ([[TopConfig]], today a single core); `flow`, `mmioRanges`, `bypass`, `bpu`, `lsqDepth` and
  * `stats` are the dump's own knobs — a knob the core's modules do not consume does not belong here either. `stats` is
  * the scope the level's statistics are attributed to: the generator's entry point hands the design's root down.
  */
case class NzeaCoreParameter(
    config: TopConfig,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    stats: StatScope
) extends Parameter

object NzeaCoreParameter:

    /** The baseline configuration: what a caller starts from before it overrides anything. A caller changes what it
      * means to change (`copy(config = …)`) and leaves the rest alone, so the knobs it has no opinion on — the
      * forwarding level, the predictor, the queue, the counter scope — are written down once, here, instead of at every
      * construction site.
      */
    def default: NzeaCoreParameter = NzeaCoreParameter(
        config = TopConfig(core = CoreConfig(isa = Isa("riscv32i"), defaultPc = 0x80000000L)),
        flow = RtlFlow.Verilator,
        // The yosys platform's device windows, RAM excluded (it is the one memory range): the addresses the core marks
        // as MMIO in the commit trace.
        mmioRanges = Seq("0x10000000:8", "0x00100000:4", "0x02000000:0xc000"),
        bypass = BypassLevel.Execute,
        bpu = Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None)),
        lsqDepth = 4,
        stats = StatScope.root
    )

    given upickle.default.ReadWriter[NzeaCoreParameter] = upickle.default.macroRW
