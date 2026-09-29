package nzea_config

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{BypassConfig, CoreConfig}
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
    bypass: BypassConfig,
    stats: StatScope
) extends Parameter:
  val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[NzeaCoreParameter] = upickle.default.macroRW
