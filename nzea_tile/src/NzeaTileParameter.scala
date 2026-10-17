package nzea_tile

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.{CacheConfig, CacheStorageKind}
import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope

/** The tile level's parameter: the core knobs it passes down, the platform's device list, and the windows the level
  * above answers.
  *
  * The tile owns its own configuration rather than borrowing a level above's — nothing generates a tile but the CLI,
  * and the knobs are the ones the tile itself consumes (what the core is built as, and what the fabric connects). Both
  * lists are the platform's: each device carries its window and whether it is memory or MMIO, each external window is
  * there to be answered from outside, and the tile derives the core's MMIO windows from them, so the map is stated once
  * and never accepted from a caller.
  */
case class NzeaTileParameter(
    config: CoreConfig,
    flow: RtlFlow,
    bypass: BypassLevel,
    bpu: Option[BpuConfig],
    lsqDepth: Int,
    icache: Option[CacheConfig],
    dcache: Option[CacheConfig],
    stats: StatScope,
    devices: Seq[DeviceSpec],
    externals: Seq[ExternalSpec]
) extends Parameter

/** The tile's cache flags (`--icache` / `--dcache`): which cache to build, or none.
  *
  * The parameter's field is an `Option` — a knob that can be absent — and a flag cannot be absent and explicit at the
  * same time: this type says both in one token, so the baseline geometry is a value like any other.
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

    /** The parameter's own value as the flag spells it. */
    def of(cache: Option[CacheConfig]): CacheChoice = cache.fold(Off)(Geometry.apply)

    def toOption(choice: CacheChoice): Option[CacheConfig] = choice match
        case Off         => None
        case Geometry(c) => Some(c)

object NzeaTileParameter:

    /** The baseline: riscv32i at `0x80000000`, verilator-shaped RTL, execute-level forwarding, the 64-entry bimodal
      * predictor, the four-entry load/store queue, the 4 KiB instruction and data caches, the counters under the
      * design's root, and the bridge flow's devices ([[NzeaTile]] states the inventory beside the wiring it feeds) —
      * with no external windows: a level that answers none is a level with nothing above it yet.
      */
    def default: NzeaTileParameter = NzeaTileParameter(
        config = CoreConfig(Isa("riscv32i"), 0x80000000L),
        flow = RtlFlow.Verilator,
        bypass = BypassLevel.Execute,
        bpu = Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None)),
        lsqDepth = 4,
        icache = Some(CacheConfig.baseline(CacheStorageKind.Registers)),
        dcache = Some(CacheConfig.baseline(CacheStorageKind.Registers)),
        stats = StatScope.root,
        devices = RtlFlow.Verilator.devices,
        externals = Seq.empty
    )

    given upickle.default.ReadWriter[NzeaTileParameter] = upickle.default.macroRW
