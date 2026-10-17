package nzea_tile

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_cache.{DCache, ICache}
import nzea_cache.config.CacheParameter
import nzea_core.{Core, CoreParameter}
import nzea_core.back.mem.{DbusUser, MemPath}
import nzea_core.config.CoreConfig
import nzea_core.front.ifu.{IbusUser, Ifu}
import nzea_core.isa.{CsrType, Gpr}
import nzea_device.DeviceContext
import nzea_device.DeviceKind
import nzea_device.connect
import nzea_device.devices.{Debugger, DebuggerParameter}
import nzea_rtl.RtlFlow
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class NzeaTileLayers(parameter: NzeaTileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class NzeaTileIO(parameter: NzeaTileParameter) extends HWRecord(parameter):
    val clock = Flipped("clock", Clock())
    val reset = Flipped("reset", Reset())

    /** What the tier above sees, one field per thing it connects: a device that shows pins gets them (the flow decides
      * which those are), and every external window gets its bus — named by the device or window, so the tile's shape is
      * exactly its two lists'.
      */
    private val map = NzeaTile.fabricMap(parameter.config, parameter.devices, parameter.externals)

    val boundary =
        parameter.devices.flatMap: d =>
            d.kind.pins(parameter.flow).map(pins => Aligned(d.name, pins))
        ++ parameter.externals.map: e =>
            Aligned(e.name, NzeaTile.externalPort(map, parameter.config))

class NzeaTileProbe(parameter: NzeaTileParameter) extends DVBundle[NzeaTileParameter, NzeaTileLayers](parameter)

/** The tile level: a [[nzea_core.Core]] behind the bus fabric, with the platform's devices on its slave side.
  *
  * The tile assembles the fabric from the core's two masters and its device list, wires the core's instruction and data
  * masters into it (the read-only side through [[LiteBusROToRW]]), builds one device per window, and lets the commit
  * trace leave to the debugger. Its ports are its two lists': `clock`/`reset`, one field per device that shows pins,
  * and one bus port per external window ([[ExternalSpec]] — the level above answers those itself). So the synthesis
  * shape carries the finisher's `finished`, the CLINT's interrupt and every external window, while the bridge flow —
  * where the simulator answers the windows it knows — exposes nothing beyond `clock`/`reset` and the external ones.
  */
@generator
object NzeaTile extends Generator[NzeaTileParameter, NzeaTileLayers, NzeaTileIO, NzeaTileProbe]:
    override def moduleName(parameter: NzeaTileParameter): String = "NzeaTile"

    def architecture(parameter: NzeaTileParameter) =
        val io = summon[Interface[NzeaTileIO]]
        val clock = io.field[Clock]("clock")
        given ClockScope = ClockScope.posedge(clock)

        val c = parameter.config
        val map = fabricMap(c, parameter.devices, parameter.externals)
        // The boundary is active-high (`NzeaCore`'s comment holds the why); the tree below is active-low.
        val resetN: Node[Reset] = (!io.field[Reset]("reset").asBool).asReset

        val core = Core.instantiate(
            CoreParameter(
                c.isa,
                c.defaultPc,
                parameter.flow,
                mmioWindows(map),
                parameter.bypass,
                parameter.bpu,
                parameter.lsqDepth,
                parameter.stats.child(Core.Source)
            )
        )
        core.io.clock := clock
        core.io.resetN := resetN

        val xbar = LiteBusCrossbar.instantiate(LiteBusCrossbarParameter(map, c.width, c.width, outstanding = 4))
        xbar.io.clock := clock
        xbar.io.resetN := resetN

        // The instruction cache, when the platform has one, sits between the fetch and the fabric; without one the
        // read-only fetch enters the uniform read-write fabric through the adapter. The cache's memory side is the
        // fabric's shape — its refill engine is a read-write master — so a cached fetch needs no adapter.
        parameter.icache match
            case Some(config) =>
                val icache = ICache.instantiate(
                    CacheParameter(
                        c.width,
                        c.width,
                        IbusUser.userWidth(c.width),
                        Ifu.busIdWidth,
                        config,
                        map.slaves.filter(_.kind == SlaveKind.Memory).map(_.range),
                        outstanding = 4,
                        parameter.flow,
                        parameter.stats.child("icache")
                    )
                )
                icache.io.clock := clock
                icache.io.resetN := resetN
                icache.io.top :<>= core.io.ibus
                xbar.io.in(map, "ibus") :<>= icache.io.bottom
            case None =>
                val ibus =
                    LiteBusROToRW.instantiate(LiteBusROToRWParameter(c.width, c.width, map.userWidth, map.idWidth))
                ibus.io.ro :<>= core.io.ibus
                xbar.io.in(map, "ibus") :<>= ibus.io.rw

        // The data master speaks a narrow tag payload; it is widened to the fabric's width and enters the fabric
        // through the adapter. The LSU holds its request until the fabric takes it, so the adapter needs no register of
        // its own to hold it off the arbitration — and one would be another cycle on every load's round trip, which the
        // in-order pipeline cannot hide. The data cache, when the platform has one, sits between the two: its memory
        // side is the adapter's narrow shape, so a cached data path needs no widening of its own.
        val dbus = LiteBusUserWiden.instantiate(
            LiteBusUserWidenParameter(c.width, c.width, DbusUser.userWidth, map.userWidth, map.idWidth)
        )
        parameter.dcache match
            case Some(config) =>
                val dcache = DCache.instantiate(
                    CacheParameter(
                        c.width,
                        c.width,
                        DbusUser.userWidth,
                        MemPath.busIdWidth,
                        config,
                        map.slaves.filter(_.kind == SlaveKind.Memory).map(_.range),
                        outstanding = 4,
                        parameter.flow,
                        parameter.stats.child("dcache")
                    )
                )
                dcache.io.clock := clock
                dcache.io.resetN := resetN
                dcache.io.top :<>= core.io.dbus
                dbus.io.narrow :<>= dcache.io.bottom
            case None =>
                dbus.io.narrow :<>= core.io.dbus
        xbar.io.in(map, "dbus") :<>= dbus.io.wide

        // The debugger is the commit trace's consumer; what the flow makes of it — the simulator in the bridge flow,
        // nothing yet in a synthesis flow — is the device's own affair, so the tile only wires it.
        val debugger =
            Debugger.instantiate(DebuggerParameter(c.width, Gpr.indexWidth, CsrType.addrWidth, parameter.flow))
        debugger.io.clock := clock
        debugger.io.resetN := resetN
        // The debugger's trace is the shared commit-trace sideband, so the two connect as one bundle.
        debugger.io.trace :<>= core.io.commit_msg

        // Each window's device, by kind: the device owns whether it answers with its hardware or — in the bridge flow —
        // with the simulator, so the tile only builds it, connects the fabric to it, and raises the pins it shows.
        for d <- parameter.devices do
            val device = d.kind.instantiate(
                parameter.flow,
                DeviceContext(c.width, c.width, map.userWidth, map.idWidth, d.range.base.toLong, d.range.size.toLong)
            )
            device.connect(clock, resetN, xbar.io.out(map, d.name))
            d.kind.pins(parameter.flow) match
                case Some(_) =>
                    val field = io.field[Bundle](d.name)
                    val boundary = device.io.field[Bundle]("boundary")
                    // A boundary may carry an input (the RAM's memory clock): inputs flow down, outputs flow up.
                    if d.kind.boundaryIsInput then field :>= boundary else field :<= boundary
                case None => d.kind.tieUnexposedBoundary(device, clock)

        // A window the level above answers: nothing is built for it — the fabric's port for the window is what leaves the
        // tile, and the level above connects its own device there. That device shares this tile's clock and reset domain
        // ([[ExternalSpec]]): the bus carries neither, and the same nets are the price of not building it here.
        for e <- parameter.externals do io.field[LiteBusRW](e.name) :<>= xbar.io.out(map, e.name)

    /** The tile's address map: the core's two masters over the platform's two lists.
      *
      * The masters are the tile's own — they are the core's buses, so their payload widths follow the core, not a
      * caller — and the windows are what a platform supplies: the devices the library builds, and the external ones the
      * level above answers. Stating both in one map is what makes a duplicate name or an overlapping window a loud
      * elaboration failure ([[AddressMap]]) instead of a decode that quietly picks one.
      */
    def fabricMap(config: CoreConfig, devices: Seq[DeviceSpec], externals: Seq[ExternalSpec]): AddressMap = AddressMap(
        masters = Seq(
            MasterPort("ibus", IbusUser.userWidth(config.width), 1),
            MasterPort("dbus", DbusUser.userWidth, 1)
        ),
        slaves = devices.map(d => SlavePort(d.name, d.range, d.kind.slaveKind, d.outstanding))
            ++ externals.map(e => SlavePort(e.name, e.range, e.slaveKind, e.outstanding))
    )

    /** The bus an external window shows at the boundary: the fabric's own shape, as the tile's *master* view of it —
      * which is what the level above connects its device's slave port to. A device of another shape is adapted where it
      * is instantiated (`nzea_rtl.bus`'s adapters), not by growing this port a width per window.
      */
    def externalPort(map: AddressMap, config: CoreConfig)(using TypeImpl, ConstructorApi): LiteBusRW =
        new LiteBusRW(config.width, config.width, map.userWidth, map.idWidth)

    /** The core's MMIO windows as the commit trace's spelling (`base:size`, hex): the map's windows with side effects.
      */
    def mmioWindows(map: AddressMap): Seq[String] =
        map.mmioRanges.map(window => s"0x${window.base.toString(16)}:0x${window.size.toString(16)}")

/** The platform's devices for a flow — the list the wiring above loops over, stated in the file whose loop reads it.
  *
  * A flow builds what it can, so the inventory is the flow's: the bridge flow gets every window — the simulator answers
  * them all, so a device with no hardware yet is still a window (the UART) — while a synthesis flow gets what has
  * hardware, in windows it can build. The two diverge on purpose; a caller that wants its own list says so.
  *
  * The inventory is the platform's, so it is stated as an extension rather than on `RtlFlow`: the flow exposes shape
  * predicates (`bridges`, `counters`), and the address map belongs to the layer that owns the map. This keeps the call
  * site reading `flow.devices` without moving platform addresses into `nzea_rtl`.
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
