package nzea_tile

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.{CacheConfig, CacheStorageKind}
import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope

/** The tile level's parameter: the core knobs it passes down, the flow whose hardware it builds, and the windows the
  * level above answers. The device inventory is not a parameter — the flow states what hardware a build has
  * ([[NzeaTile]]'s `devices` extension) — and answering a window is an *increment* on it: an external entry shadows the
  * same-named device, so a caller adds to [[externals]] and never prunes the flow's list. The tile derives the core's
  * MMIO windows from these two lists, so the map is stated once and never accepted from a caller. The clock rate is the
  * level's own fact (the tile is handed a clock, not a frequency): the devices whose timing is a rate — the UART's bit
  * period — derive it from [[clockHz]]. [[timeHz]] is the platform's second rate: what the CLINT counts at, which is
  * deliberately not the clock's (a core's clock is a design decision, a platform's time is not).
  */
case class NzeaTileParameter(
    config: CoreConfig,
    flow: RtlFlow,
    clockHz: Int,
    timeHz: Int,
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    icache: Option[CacheConfig],
    dcache: Option[CacheConfig],
    stats: StatScope,
    externals: Seq[ExternalSpec]
) extends Parameter:

    /** What this tile builds: the flow's hardware minus the windows the level above answers. A name is a window's
      * identity, so an external entry shadows the flow's device of that name — the map keeps exactly one entry per
      * window, and forgetting to prune cannot produce two.
      */
    def built: Seq[DeviceSpec] = flow.devices.filterNot(d => externals.exists(_.name == d.name))

/** The tile's cache flags (`--icache` / `--dcache`): which cache to build, or none. A flag cannot be absent and
  * explicit at the same time, so this type says both in one token — `none` is a value like any other.
  */
enum CacheChoice:
    case Off
    case Geometry(config: CacheConfig)

object CacheChoice:

    given TokensReader.Simple[CacheChoice] with
        def shortName = "none|setsxwaysxlineBytes:replacement:storage"

        def read(strs: Seq[String]): Either[String, CacheChoice] =
            strs.lastOption match
                case None                                            => Left("cache requires a value")
                case Some(token) if token.trim.toLowerCase == "none" => Right(Off)
                case Some(token)                                     => CacheConfig.parse(token).map(Geometry.apply)

    def of(cache: Option[CacheConfig]): CacheChoice = cache.fold(Off)(Geometry.apply)

    def toOption(choice: CacheChoice): Option[CacheConfig] = choice match
        case Off         => None
        case Geometry(c) => Some(c)

object NzeaTileParameter:

    /** The baseline: the tile's own values, the bridge flow's hardware ([[NzeaTile]] states the inventory beside the
      * wiring it reads), the rate a bridge-flow build ignores (no device hardware is built there), and no external
      * windows — a level that answers none is a level with nothing above it yet.
      */
    def default: NzeaTileParameter = NzeaTileParameter(
        config = CoreConfig(Isa("riscv32i"), 0x80000000L),
        flow = RtlFlow.Verilator,
        clockHz = 25000000,
        timeHz = 1000000,
        bypass = BypassLevel.Execute,
        bpu = Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None)),
        lsqDepth = 4,
        icache = Some(CacheConfig.baseline(CacheStorageKind.Registers)),
        dcache = Some(CacheConfig.baseline(CacheStorageKind.Registers)),
        stats = StatScope.root,
        externals = Seq.empty
    )

    given upickle.default.ReadWriter[NzeaTileParameter] = upickle.default.macroRW
