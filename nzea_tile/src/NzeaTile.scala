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
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class NzeaTileLayers(parameter: NzeaTileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class NzeaTileIO(parameter: NzeaTileParameter) extends HWRecord(parameter):
    val clock = Flipped("clock", Clock())
    val reset = Flipped("reset", Reset())

    /** The counter's own reset ([[nzea_device.devices.Clint]]): the tile's reset is a load's hold as well, and a load
      * must not set the platform's clock back. Only a build that carries hardware has it — the bridge flow's timer is
      * the simulator's, so that shape keeps its two ports.
      */
    private val timeReset = if parameter.flow.bridges then None else Some(Flipped("timeReset", Reset()))

    /** What the tier above sees, one field per thing it connects: a device this tile builds that shows pins gets them
      * (the flow decides which those are), and every external window gets its bus — named by the device or window, so
      * the tile's shape is exactly its built devices and the windows the level answers.
      */
    private val map = NzeaTile.fabricMap(parameter)

    val boundary =
        parameter.built.flatMap: d =>
            d.kind.pins(parameter.flow).map(pins => Aligned(d.name, pins))
        ++ parameter.externals.map: e =>
            Aligned(e.name, NzeaTile.externalPort(map, parameter.config))

class NzeaTileProbe(parameter: NzeaTileParameter) extends DVBundle[NzeaTileParameter, NzeaTileLayers](parameter)

/** The tile level: a [[nzea_core.Core]] behind the bus fabric, with the platform's devices on its slave side. Its ports
  * are its two lists': `clock`/`reset` (and, in a build that carries hardware, the counter's own `timeReset`), one
  * field per device that shows pins, and one bus port per external window ([[ExternalSpec]] — the level above answers
  * those itself). So the synthesis shape carries the finisher's `finished`, the CLINT's interrupt and every external
  * window, while the bridge flow — where the simulator answers the windows it knows — exposes nothing beyond
  * `clock`/`reset` and the external ones.
  */
@generator
object NzeaTile extends Generator[NzeaTileParameter, NzeaTileLayers, NzeaTileIO, NzeaTileProbe]:
    override def moduleName(parameter: NzeaTileParameter): String = "NzeaTile"

    def architecture(parameter: NzeaTileParameter) =
        val io = summon[Interface[NzeaTileIO]]
        val clock = io.field[Clock]("clock")
        given ClockScope = ClockScope.posedge(clock)

        val c = parameter.config
        val map = fabricMap(parameter)
        // The boundary is active-high (`NzeaCore`'s comment holds the why); the tree below is active-low.
        val resetN: Node[Reset] = (!io.field[Reset]("reset").asBool).asReset
        // The counter's reset is the platform's own; a bridge-flow build has no separate one and hands it this reset.
        val timeResetN: Node[Reset] =
            if parameter.flow.bridges then resetN else (!io.field[Reset]("timeReset").asBool).asReset

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
        // read-only fetch enters the read-write fabric through the adapter. The cache's refill engine is already a
        // read-write master, so a cached fetch needs no adapter.
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

        // The data master speaks a narrow tag payload, widened to the fabric's width by the adapter. The LSU holds its
        // request until the fabric takes it, so the adapter needs no register to hold it off the arbitration — one
        // would be another cycle on every load's round trip, which the in-order pipeline cannot hide. The data cache,
        // when the platform has one, sits between the two: its memory side is the adapter's narrow shape.
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

        val debugger =
            Debugger.instantiate(DebuggerParameter(c.width, Gpr.indexWidth, CsrType.addrWidth, parameter.flow))
        debugger.io.clock := clock
        debugger.io.resetN := resetN
        // The debugger's trace is the shared commit-trace sideband, so the two connect as one bundle.
        debugger.io.trace :<>= core.io.commit_msg

        for d <- parameter.built do
            // An instance's name is the given `sourcecode.Name.Machine`; without the per-device shadow every instance
            // lands as `device`, and the dedup suffixes (`device_0`, …) make the waveform unreadable.
            given sourcecode.Name.Machine = sourcecode.Name.Machine(d.name)
            val device = d.kind.instantiate(
                parameter.flow,
                DeviceContext(
                    c.width,
                    c.width,
                    map.userWidth,
                    map.idWidth,
                    d.range.base.toLong,
                    d.range.size.toLong,
                    parameter.clockHz,
                    parameter.timeHz
                )
            )
            device.connect(clock, resetN, xbar.io.out(map, d.name))
            // The CLINT's counter is the one state a load may not clear: same clock, the platform's reset ([[ClintIO]]).
            if d.kind == DeviceKind.Clint then device.io.field[Reset]("timeResetN") := timeResetN
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

    /** The tile's address map: the core's two masters over the windows this tile builds ([[NzeaTileParameter.built]])
      * and the ones the level above answers. Stating both in one map is what makes a duplicate name or an overlapping
      * window a loud elaboration failure ([[AddressMap]]) instead of a decode that quietly picks one.
      */
    def fabricMap(parameter: NzeaTileParameter): AddressMap = AddressMap(
        masters = Seq(
            MasterPort("ibus", IbusUser.userWidth(parameter.config.width), 1),
            MasterPort("dbus", DbusUser.userWidth, 1)
        ),
        slaves = parameter.built.map(d => SlavePort(d.name, d.range, d.kind.slaveKind, d.outstanding))
            ++ parameter.externals.map(e => SlavePort(e.name, e.range, e.slaveKind, e.outstanding))
    )

    /** The bus an external window shows at the boundary: the fabric's own shape, as the tile's *master* view of it —
      * which is what the level above connects its device's slave port to. A device of another shape is adapted where it
      * is instantiated, not by growing this port a width per window.
      */
    def externalPort(map: AddressMap, config: CoreConfig)(using TypeImpl, ConstructorApi): LiteBusRW =
        new LiteBusRW(config.width, config.width, map.userWidth, map.idWidth)

    /** The core's MMIO windows as the commit trace's spelling (`base:size`, hex): the map's windows with side effects.
      */
    def mmioWindows(map: AddressMap): Seq[String] =
        map.mmioRanges.map(window => s"0x${window.base.toString(16)}:0x${window.size.toString(16)}")
