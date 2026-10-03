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
import nzea_device.connect
import nzea_device.clint.{Clint, ClintParameter}
import nzea_device.dpi.{Debugger, DebuggerParameter}
import nzea_device.finisher.{SifiveTestFinisher, SifiveTestFinisherParameter}
import nzea_device.ram.{RamFabricSlave, RamFabricSlaveParameter}
import nzea_device.uart.{Uart, UartParameter}
import nzea_rtl.bus.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class NzeaTileLayers(parameter: NzeaTileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class NzeaTileIO(parameter: NzeaTileParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val reset = Flipped(Reset())

class NzeaTileProbe(parameter: NzeaTileParameter) extends DVBundle[NzeaTileParameter, NzeaTileLayers](parameter)

/** The tile level: a [[nzea_core.Core]] behind the bus fabric, with every slave answered by a simulation bridge.
  *
  * This is the minimal shape — the one the simulator loads. The tile assembles the fabric from the core's two masters
  * and the platform's device list, wires the core's instruction and data masters into it (the read-only side through
  * [[LiteBusROToRW]]), answers every slave port with a `bus_read`/`bus_write` bridge, and lets the commit trace leave
  * through `commit_trace`. No device RTL is instantiated: the platform's devices live outside the FPGA and are the
  * simulator's to answer, which is exactly what the bridges reach. The interfaces-as-ports shape (a synthesis top) is a
  * later step, so a flow that does not bridge is refused rather than silently mis-generated.
  */
@generator
object NzeaTile extends Generator[NzeaTileParameter, NzeaTileLayers, NzeaTileIO, NzeaTileProbe]:
    override def moduleName(parameter: NzeaTileParameter): String = "NzeaTile"

    def architecture(parameter: NzeaTileParameter) =
        val io = summon[Interface[NzeaTileIO]]
        given ClockScope = ClockScope.posedge(io.clock)

        val c = parameter.config
        val map = fabricMap(c, parameter.devices)
        // The boundary is active-high (`NzeaCore`'s comment holds the why); the tree below is active-low.
        val resetN: Node[Reset] = (!io.reset.asBool).asReset

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
        core.io.clock := io.clock
        core.io.resetN := resetN

        val xbar = LiteBusCrossbar.instantiate(LiteBusCrossbarParameter(map, c.width, c.width, outstanding = 4))
        xbar.io.clock := io.clock
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

        ibusSlice.io.clock := io.clock
        ibusSlice.io.resetN := resetN

        val debugger = Debugger.instantiate(DebuggerParameter(c.width, Gpr.indexWidth, CsrType.addrWidth))
        debugger.io.clock := io.clock
        debugger.io.resetN := resetN
        // The debugger's trace is the shared commit-trace sideband, so the two connect as one bundle.
        debugger.io.trace :<>= core.io.commit_msg

        // Each window's device, by name: the device owns whether it answers with its hardware or — in the bridge flow —
        // with the simulator, so the tile only connects the fabric to devices and never touches DPI itself.
        for slave <- map.slaves do
            val base = slave.range.base.toLong
            val device: Instance[?, ?] = slave.name match
                case "ram" =>
                    RamFabricSlave.instantiate(
                        RamFabricSlaveParameter(c.width, c.width, map.userWidth, map.idWidth, base, parameter.flow)
                    )
                case "uart16550" =>
                    Uart.instantiate(UartParameter(c.width, c.width, map.userWidth, map.idWidth, base, parameter.flow))
                case "finisher" =>
                    SifiveTestFinisher.instantiate(
                        SifiveTestFinisherParameter(c.width, c.width, map.userWidth, map.idWidth, parameter.flow)
                    )
                case "clint" =>
                    Clint.instantiate(
                        ClintParameter(c.width, c.width, map.userWidth, map.idWidth, base, parameter.flow)
                    )
                case other =>
                    throw new IllegalArgumentException(s"no device is ported for the '$other' window")
            device.connect(io.clock, resetN, xbar.io.out(map, slave.name))

    /** The tile's address map: the core's two masters over the platform's device list.
      *
      * The masters are the tile's own — they are the core's buses, so their payload widths follow the core, not a
      * caller — and the devices are what a platform supplies.
      */
    def fabricMap(config: CoreConfig, devices: Seq[SlavePort]): AddressMap = AddressMap(
        masters = Seq(
            MasterPort("ibus", IbusUser.userWidth(config.width), 1),
            MasterPort("dbus", DbusUser.userWidth, 1)
        ),
        slaves = devices
    )

    /** The core's MMIO windows as the commit trace's spelling (`base:size`, hex): the map's windows with side effects.
      */
    def mmioWindows(map: AddressMap): Seq[String] =
        map.mmioRanges.map(window => s"0x${window.base.toString(16)}:0x${window.size.toString(16)}")
