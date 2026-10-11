package nzea_tile

import mainargs.TokensReader
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.config.{CacheConfig, CacheStorageKind}
import nzea_device.DeviceKind
import nzea_core.config.{BpuConfig, BpuStrategy, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.bus.AddrRange
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.SyncRamPorts

/** The tile level's parameter: the core knobs it passes down and the platform's device list.
  *
  * The tile owns its own configuration rather than borrowing a level above's — nothing generates a tile but the CLI,
  * and the knobs are the ones the tile itself consumes (what the core is built as, and what the fabric connects). The
  * device list is the platform's: each device carries its window and whether it is memory or MMIO, and the tile derives
  * the core's MMIO windows from it, so the map is stated once and never accepted from a caller.
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
    devices: Seq[DeviceSpec]
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
      * design's root, and the bridge flow's devices.
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
        devices = RtlFlow.Verilator.devices
    )

    given upickle.default.ReadWriter[NzeaTileParameter] = upickle.default.macroRW

/** The platform's devices for a flow.
  *
  * A flow builds what it can, so the inventory is the flow's: the bridge flow gets every window — the simulator answers
  * them all, so a device with no hardware yet is still a window (the UART) — while a synthesis flow gets what has
  * hardware, in windows it can build. The two diverge on purpose; a caller that wants its own list says so.
  *
  * The inventory is the platform's, so it is stated here as an extension rather than on `RtlFlow`: the flow exposes
  * shape predicates (`bridges`, `counters`), and the address map belongs to the layer that owns the map. This keeps the
  * call site reading `flow.devices` without moving platform addresses into `nzea_rtl`.
  */
extension (flow: RtlFlow)

    def devices: Seq[DeviceSpec] = flow match
        case RtlFlow.Verilator =>
            // The bridge flow: the whole platform, every window answered by the simulator.
            Seq(
                DeviceSpec(
                    "ram",
                    AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)),
                    DeviceKind.Ram(SyncRamPorts.OneRW)
                ),
                DeviceSpec("uart16550", AddrRange(BigInt("10000000", 16), BigInt("00000008", 16)), DeviceKind.Uart),
                DeviceSpec("finisher", AddrRange(BigInt("00100000", 16), BigInt("00000004", 16)), DeviceKind.Finisher),
                DeviceSpec("clint", AddrRange(BigInt("02000000", 16), BigInt("0000c000", 16)), DeviceKind.Clint)
            )
        case RtlFlow.Yosys =>
            // The synthesis flow: what has hardware. The UART is left out (its hardware is not ported), and the RAM's
            // window is its storage — a window worth building, not the 128 MiB the simulator models.
            Seq(
                DeviceSpec(
                    "ram",
                    AddrRange(BigInt("80000000", 16), BigInt("00020000", 16)),
                    DeviceKind.Ram(SyncRamPorts.OneRW)
                ),
                DeviceSpec("finisher", AddrRange(BigInt("00100000", 16), BigInt("00000004", 16)), DeviceKind.Finisher),
                DeviceSpec("clint", AddrRange(BigInt("02000000", 16), BigInt("0000c000", 16)), DeviceKind.Clint)
            )
