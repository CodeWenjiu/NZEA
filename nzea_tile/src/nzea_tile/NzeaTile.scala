package nzea_tile

import chisel3._
import chisel3.util.Valid
import nzea_config.SynthPlatform
import nzea_core.config.CoreConfig
import nzea_core.dpi.{CommitDpiBridge, DbusDpiBridge}
import nzea_core.retire.CommitMsg
import nzea_rtl.{
  FabricAddrRange,
  FabricBusRW,
  FabricBusRWCrossbar,
  FabricBusRWRegisterSlice,
  FabricRWToLiteRW,
  LiteBusROReqRegisterSlice,
  LiteBusROToFabricRW,
  LiteBusRWToFabricRW
}

/** Tile address map.
  *
  * +----------------------+-------------------------+
  * | name                 | range                   |
  * +----------------------+-------------------------+
  * | ram                  | 0x80000000:0x88000000   |
  * | uart16550            | 0x10000000:0x10000008   |
  * | sifive_test_finisher | 0x00100000:0x00100004   |
  * | clint                | 0x02000000:0x0200c000   |
  * +----------------------+-------------------------+
  */
object TileAddressMap {
  object Yosys {
    val ram = FabricAddrRange(base = BigInt("80000000", 16), size = BigInt("08000000", 16))
    val uart16550 = FabricAddrRange(base = BigInt("10000000", 16), size = BigInt("00000008", 16))
    val sifiveTestFinisher = FabricAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
    val clint = FabricAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
    val ranges: Seq[FabricAddrRange] = Seq(ram, uart16550, sifiveTestFinisher, clint)
  }

  def forPlatform(platform: SynthPlatform): Seq[FabricAddrRange] = platform match {
    case SynthPlatform.Yosys => Yosys.ranges
    case SynthPlatform.HelloFPGA => HelloFpgaAddressMap.ranges
  }
}

/** External device bus ports (non-sim path). */
class TileDeviceBusBundle(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val ram = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val uart16550 = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val sifive_test_finisher = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
  val clint = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
}

/** Wrapper around [[DbusDpiBridge]] for Fabric xbar slave-side use.
  * Uses Fabric->Lite conversion and breaks req.flush combinational feedback by forcing slave req.flush to false.
  */
class TileSimDeviceDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth))
  })
  private val toLite = Module(new FabricRWToLiteRW(
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    fabricUserWidth = userWidth,
    idWidth = idWidth,
    liteUserWidth = userWidth
  ))
  private val bridge = Module(new DbusDpiBridge(addrWidth, dataWidth, userWidth))

  // Request side: break req.flush combinational loop at tile/device boundary.
  toLite.io.in.req.valid := io.bus.req.valid
  toLite.io.in.req.bits := io.bus.req.bits
  io.bus.req.ready := toLite.io.in.req.ready
  io.bus.req.flush := false.B

  // Response side keeps normal flush propagation.
  toLite.io.in.resp.flush := io.bus.resp.flush
  io.bus.resp.valid := toLite.io.in.resp.valid
  io.bus.resp.bits := toLite.io.in.resp.bits
  toLite.io.in.resp.ready := io.bus.resp.ready

  bridge.io.bus <> toLite.io.out
}

/** CPU tile: [[nzea_core.Core]] plus FabricBus interconnect and per-device external interfaces.
  * Bus fabric: 2 masters (core ibus+dbus) and platform-selected slaves.
  * `sim=true`: slaves are connected to DPI RW bridges (bus_read/bus_write).
  * `sim=false`: Yosys exposes per-device FabricBusRW ports; HelloFPGA instantiates on-chip device blackboxes.
  */
class NzeaTile(sim: Boolean, platform: SynthPlatform)(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ranges = TileAddressMap.forPlatform(platform)

  val core = Module(new nzea_core.Core)
  private val fabricUserWidth = core.io.ibus.userWidth.max(core.io.dbus.userWidth)
  // 8-bit request ID is enough for current tile-level outstanding needs and leaves room for growth.
  private val fabricIdWidth = 8

  val ibusReqSlice = Module(new LiteBusROReqRegisterSlice(
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    userWidth = core.io.ibus.userWidth
  ))
  ibusReqSlice.io.in <> core.io.ibus

  val ibusToFabric = Module(new LiteBusROToFabricRW(
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    liteUserWidth = core.io.ibus.userWidth,
    fabricUserWidth = fabricUserWidth,
    idWidth = fabricIdWidth
  ))
  ibusToFabric.io.in <> ibusReqSlice.io.out

  val dbusToFabric = Module(new LiteBusRWToFabricRW(
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    liteUserWidth = core.io.dbus.userWidth,
    fabricUserWidth = fabricUserWidth,
    idWidth = fabricIdWidth
  ))
  dbusToFabric.io.in <> core.io.dbus

  val fabric = Module(new FabricBusRWCrossbar(
    numMasters = 2,
    addrWidth = addrWidth,
    dataWidth = dataWidth,
    userWidth = fabricUserWidth,
    idWidth = fabricIdWidth,
    ranges = ranges,
    perSlaveOutstanding = 8
  ))

  // Keep sim and non-sim topology identical at the tile boundary.
  // Always insert the same bus register slices before entering the fabric.
  val ibusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  val dbusSlice = Module(new FabricBusRWRegisterSlice(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
  ibusSlice.io.in <> ibusToFabric.io.out
  dbusSlice.io.in <> dbusToFabric.io.out
  fabric.io.in(0) <> ibusSlice.io.out
  fabric.io.in(1) <> dbusSlice.io.out

  if (sim) {
    platform match {
      case SynthPlatform.Yosys =>
        val ram = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val uart = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val finisher = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val clint = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
        fabric.io.out(2) <> finisher.io.bus
        fabric.io.out(3) <> clint.io.bus

      case SynthPlatform.HelloFPGA =>
        val ram = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        val uart = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
    }

    val cb = Module(new CommitDpiBridge)
    cb.io.commit_msg := core.io.commit_msg
  } else {
    val commit_msg = IO(Output(Valid(new CommitMsg)))
    commit_msg := core.io.commit_msg

    platform match {
      case SynthPlatform.Yosys =>
        val devices = IO(new TileDeviceBusBundle(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))
        fabric.io.out(0) <> devices.ram
        fabric.io.out(1) <> devices.uart16550
        fabric.io.out(2) <> devices.sifive_test_finisher
        fabric.io.out(3) <> devices.clint

      case SynthPlatform.HelloFPGA =>
        val fpga = IO(new Bundle {
          val uart = new HelloFpgaUartIo
        })

        val ram = Module(new HelloFpgaRamFabricSlave(
          addrWidth = addrWidth,
          dataWidth = dataWidth,
          userWidth = fabricUserWidth,
          idWidth = fabricIdWidth,
          baseAddr = HelloFpgaAddressMap.ram.base
        ))
        val uart = Module(new HelloFpgaUartFabricSlave(
          addrWidth = addrWidth,
          dataWidth = dataWidth,
          userWidth = fabricUserWidth,
          idWidth = fabricIdWidth,
          baseAddr = HelloFpgaAddressMap.uart.base
        ))

        fabric.io.out(0) <> ram.io.bus
        fabric.io.out(1) <> uart.io.bus
        fpga.uart <> uart.io.uart
    }
  }
}
