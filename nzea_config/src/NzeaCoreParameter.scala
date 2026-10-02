package nzea_config

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuLevel, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.{RtlFlow, StatScope}

/** The core level's configuration: the modules' own configs, assembled into the one bundle a level top is generated
  * from.
  *
  * Every knob is declared where it is consumed — the ISA and the register width in `nzea_core.isa`, the bypass links
  * beside the read network that asks for them, the flow in `nzea_rtl` — and this is the one place that names them
  * together, so a tile above the core takes one bundle instead of a list of loose arguments. A knob the core's modules
  * do not consume does not belong here either. `stats` is the scope the level's statistics are attributed to: the
  * generator's entry point hands the design's root down.
  */
case class NzeaCoreParameter(
    isa: Isa,
    defaultPc: Long,
    flow: RtlFlow,
    mmioRanges: Seq[String],
    bypass: BypassLevel,
    bpu: BpuLevel,
    stats: StatScope
) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

object NzeaCoreParameter:

    /** The baseline configuration: what a caller starts from before it overrides anything — riscv32i at `0x80000000`,
      * verilator-shaped RTL, the yosys platform's device windows, execute-level forwarding, the 64-entry bimodal
      * predictor, and the counters hanging under the design's root scope.
      *
      * A caller changes what it means to change (`NzeaCoreParameter.default.copy(isa = …)`) and leaves the rest alone,
      * so the knobs it has no opinion on — the forwarding level, the predictor, the counter scope — are written down
      * once, here, instead of at every construction site. None of them is a CLI flag yet: a different design is a
      * different value here.
      */
    def default: NzeaCoreParameter = NzeaCoreParameter(
        isa = Isa("riscv32i"),
        defaultPc = 0x80000000L,
        flow = RtlFlow.Verilator,
        // The yosys platform's device windows, RAM excluded (it is the one memory range): the addresses the core marks
        // as MMIO in the commit trace. The tile owns this map once it lands.
        mmioRanges = Seq("0x10000000:8", "0x00100000:4", "0x02000000:0xc000"),
        bypass = BypassLevel.Execute,
        bpu = BpuLevel.Bimodal(64, 4),
        stats = StatScope.root
    )

given upickle.default.ReadWriter[NzeaCoreParameter] = upickle.default.macroRW
