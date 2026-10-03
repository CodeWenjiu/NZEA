package nzea_tile

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.{BpuLevel, BypassLevel, CoreConfig}
import nzea_core.isa.Isa
import nzea_rtl.RtlFlow
import nzea_rtl.bus.{AddrRange, SlaveKind, SlavePort}
import nzea_rtl.stat.StatScope

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
    bpu: BpuLevel,
    stats: StatScope,
    devices: Seq[SlavePort]
) extends Parameter

object NzeaTileParameter:

    /** The baseline: riscv32i at `0x80000000`, verilator-shaped RTL, execute-level forwarding, the 64-entry bimodal
      * predictor, the counters under the design's root, and the yosys platform's devices.
      */
    def default: NzeaTileParameter = NzeaTileParameter(
        config = CoreConfig(Isa("riscv32i"), 0x80000000L),
        flow = RtlFlow.Verilator,
        bypass = BypassLevel.Execute,
        bpu = BpuLevel.Bimodal(64, 4),
        stats = StatScope.root,
        devices = yosysDevices
    )

    /** The yosys platform's devices: RAM at `0x8000_0000` and the three windows the platform answers outside memory, in
      * the order the platform lays them out.
      */
    def yosysDevices: Seq[SlavePort] = Seq(
        SlavePort("ram", AddrRange(BigInt("80000000", 16), BigInt("08000000", 16)), SlaveKind.Memory),
        SlavePort("uart16550", AddrRange(BigInt("10000000", 16), BigInt("00000008", 16)), SlaveKind.Mmio),
        SlavePort("finisher", AddrRange(BigInt("00100000", 16), BigInt("00000004", 16)), SlaveKind.Mmio),
        SlavePort("clint", AddrRange(BigInt("02000000", 16), BigInt("0000c000", 16)), SlaveKind.Mmio)
    )

    given upickle.default.ReadWriter[NzeaTileParameter] = upickle.default.macroRW
