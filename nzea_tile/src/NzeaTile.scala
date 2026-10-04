package nzea_tile

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.{Core, CoreParameter}
import nzea_core.back.mem.DbusUser
import nzea_core.config.CoreConfig
import nzea_core.front.ifu.IbusUser
import nzea_core.isa.{CsrType, Gpr}
import nzea_device.DeviceContext
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

    /** One field per device that shows pins, named by the device; the flow decides which those are, so the tile's shape
      * is exactly its device list's.
      */
    val boundary =
        parameter.devices.flatMap: d =>
            d.kind.pins(parameter.flow).map(pins => Aligned(d.name, pins))

class NzeaTileProbe(parameter: NzeaTileParameter) extends DVBundle[NzeaTileParameter, NzeaTileLayers](parameter)

/** The tile level: a [[nzea_core.Core]] behind the bus fabric, with the platform's devices on its slave side.
  *
  * The tile assembles the fabric from the core's two masters and its device list, wires the core's instruction and data
  * masters into it (the read-only side through [[LiteBusROToRW]]), builds one device per window, and lets the commit
  * trace leave to the debugger. Its ports are its device list's: `clock`/`reset`, plus one field per device that shows
  * pins. So the synthesis shape carries the finisher's `finished` and the CLINT's interrupt, while the bridge flow —
  * where the simulator answers every window — exposes nothing beyond `clock`/`reset`.
  */
@generator
object NzeaTile extends Generator[NzeaTileParameter, NzeaTileLayers, NzeaTileIO, NzeaTileProbe]:
    override def moduleName(parameter: NzeaTileParameter): String = "NzeaTile"

    def architecture(parameter: NzeaTileParameter) =
        val io = summon[Interface[NzeaTileIO]]
        val clock = io.field[Clock]("clock")
        given ClockScope = ClockScope.posedge(clock)

        val c = parameter.config
        val map = fabricMap(c, parameter.devices)
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
                parameter.stats.child(Core.Source)
            )
        )
        core.io.clock := clock
        core.io.resetN := resetN

        val xbar = LiteBusCrossbar.instantiate(LiteBusCrossbarParameter(map, c.width, c.width, outstanding = 4))
        xbar.io.clock := clock
        xbar.io.resetN := resetN

        // The instruction master is read-only; the fabric is uniform, so it enters through the adapter. Its payload is
        // the map's widest (the fetch carries the most), so no widening is needed on this side. The slice before the
        // fabric holds the request off the fabric's arbitration: the fetch's request valid follows the pipeline's ready
        // (the IDU, which a load's answer feeds through the bypass network), and a fabric's grant couples that to every
        // other master's valid — so without the slice the two masters close a loop through the core.
        val ibus = LiteBusROToRW.instantiate(LiteBusROToRWParameter(c.width, c.width, map.userWidth, map.idWidth))
        val ibusSlice = LiteBusRegisterSlice.instantiate(
            LiteBusRegisterSliceParameter(c.width, c.width, map.userWidth, map.idWidth)
        )
        ibus.io.ro :<>= core.io.ibus
        ibusSlice.io.in :<>= ibus.io.rw
        xbar.io.in(map, "ibus") :<>= ibusSlice.io.out

        // The data master speaks a narrow tag payload; it is widened to the fabric's width and enters the fabric
        // directly. The LSU holds its request until the fabric takes it, so no register is needed to hold it off the
        // arbitration — and a register here would be one more cycle on every load's round trip, which the in-order
        // pipeline cannot hide.
        val dbus = LiteBusUserWiden.instantiate(
            LiteBusUserWidenParameter(c.width, c.width, DbusUser.userWidth, map.userWidth, map.idWidth)
        )
        dbus.io.narrow :<>= core.io.dbus
        xbar.io.in(map, "dbus") :<>= dbus.io.wide

        ibusSlice.io.clock := clock
        ibusSlice.io.resetN := resetN

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
                case Some(_) => io.field[Bundle](d.name) :<= device.io.field[Bundle]("boundary")
                case None    => ()

    /** The tile's address map: the core's two masters over the platform's device list.
      *
      * The masters are the tile's own — they are the core's buses, so their payload widths follow the core, not a
      * caller — and the devices are what a platform supplies.
      */
    def fabricMap(config: CoreConfig, devices: Seq[DeviceSpec]): AddressMap = AddressMap(
        masters = Seq(
            MasterPort("ibus", IbusUser.userWidth(config.width), 1),
            MasterPort("dbus", DbusUser.userWidth, 1)
        ),
        slaves = devices.map(d => SlavePort(d.name, d.range, d.kind.slaveKind, d.outstanding))
    )

    /** The core's MMIO windows as the commit trace's spelling (`base:size`, hex): the map's windows with side effects.
      */
    def mmioWindows(map: AddressMap): Seq[String] =
        map.mmioRanges.map(window => s"0x${window.base.toString(16)}:0x${window.size.toString(16)}")
