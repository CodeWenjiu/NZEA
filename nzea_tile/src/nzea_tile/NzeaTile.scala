package nzea_tile

import chisel3._
import chisel3.util.Valid
import chisel3.util.Cat
import nzea_core.config.CoreConfig
import nzea_core.dpi.{CommitDpiBridge, DbusDpiBridge}
import nzea_core.retire.CommitMsg
import nzea_rtl.{LiteBusAddrRange, LiteBusROToRW, LiteBusRW, LiteBusRWCrossbar}

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
  val ram = LiteBusAddrRange(base = BigInt("80000000", 16), size = BigInt("08000000", 16))
  val uart16550 = LiteBusAddrRange(base = BigInt("10000000", 16), size = BigInt("00000008", 16))
  val sifiveTestFinisher = LiteBusAddrRange(base = BigInt("00100000", 16), size = BigInt("00000004", 16))
  val clint = LiteBusAddrRange(base = BigInt("02000000", 16), size = BigInt("0000c000", 16))
  val ranges: Seq[LiteBusAddrRange] = Seq(ram, uart16550, sifiveTestFinisher, clint)
}

/** External device bus ports (non-sim path). */
class TileDeviceBusBundle(addrWidth: Int, dataWidth: Int, userWidth: Int) extends Bundle {
  val ram = new LiteBusRW(addrWidth, dataWidth, userWidth)
  val uart16550 = new LiteBusRW(addrWidth, dataWidth, userWidth)
  val sifive_test_finisher = new LiteBusRW(addrWidth, dataWidth, userWidth)
  val clint = new LiteBusRW(addrWidth, dataWidth, userWidth)
}

/** Adapt LiteBusRW user width to a common fabric width.
  * Request user is zero-extended/truncated; response user is truncated back to inUserWidth.
  */
class LiteBusRwUserWidthAdapter(addrWidth: Int, dataWidth: Int, inUserWidth: Int, outUserWidth: Int) extends Module {
  require(inUserWidth > 0, s"inUserWidth must be > 0, got $inUserWidth")
  require(outUserWidth > 0, s"outUserWidth must be > 0, got $outUserWidth")

  val io = IO(new Bundle {
    val in = Flipped(new LiteBusRW(addrWidth, dataWidth, inUserWidth))
    val out = new LiteBusRW(addrWidth, dataWidth, outUserWidth)
  })

  io.out.req.valid := io.in.req.valid
  io.out.req.bits.addr := io.in.req.bits.addr
  io.out.req.bits.wdata := io.in.req.bits.wdata
  io.out.req.bits.wen := io.in.req.bits.wen
  io.out.req.bits.wstrb := io.in.req.bits.wstrb
  io.out.req.bits.user := {
    if (outUserWidth == inUserWidth) io.in.req.bits.user
    else if (outUserWidth > inUserWidth) Cat(0.U((outUserWidth - inUserWidth).W), io.in.req.bits.user)
    else io.in.req.bits.user(outUserWidth - 1, 0)
  }
  io.in.req.ready := io.out.req.ready
  io.in.req.flush := io.out.req.flush

  io.in.resp.valid := io.out.resp.valid
  io.in.resp.bits.data := io.out.resp.bits.data
  io.in.resp.bits.user := {
    if (outUserWidth >= inUserWidth) io.out.resp.bits.user(inUserWidth - 1, 0)
    else Cat(0.U((inUserWidth - outUserWidth).W), io.out.resp.bits.user)
  }
  io.out.resp.ready := io.in.resp.ready
  io.out.resp.flush := io.in.resp.flush
}

/** Direction adapter for exposing a slave-side bus as top-level IO while still passing
  * a Flipped endpoint to auto-link builder.
  */
class TileSlaveTap(addrWidth: Int, dataWidth: Int, userWidth: Int) extends Module {
  val io = IO(new Bundle {
    val ext = new LiteBusRW(addrWidth, dataWidth, userWidth)
    val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth))
  })
  io.bus <> io.ext
}

/** Wrapper around [[DbusDpiBridge]] for xbar slave-side use.
  * Breaks req.flush combinational feedback by forcing slave req.flush to false.
  */
class TileSimDeviceDpiBridge(addrWidth: Int, dataWidth: Int, userWidth: Int) extends Module {
  val io = IO(new Bundle {
    val bus = Flipped(new LiteBusRW(addrWidth, dataWidth, userWidth))
  })
  private val bridge = Module(new DbusDpiBridge(addrWidth, dataWidth, userWidth))

  bridge.io.bus.req.valid := io.bus.req.valid
  bridge.io.bus.req.bits := io.bus.req.bits
  io.bus.req.ready := bridge.io.bus.req.ready
  io.bus.req.flush := false.B

  io.bus.resp.valid := bridge.io.bus.resp.valid
  io.bus.resp.bits := bridge.io.bus.resp.bits
  bridge.io.bus.resp.ready := io.bus.resp.ready
  bridge.io.bus.resp.flush := io.bus.resp.flush
}

/** Tile-level observability (optional taps). */
class TileStatusBundle extends Bundle {
  /** High while tile exposes a valid commit message on the core tap (same as core). */
  val commit_msg_valid = Output(Bool())
}

/** CPU tile: [[nzea_core.Core]] plus LiteBus fabric and per-device external interfaces.
  * Bus fabric: 2 masters (core ibus+dbus) -> 4 slaves (ram/uart16550/sifive_test_finisher/clint).
  * `sim=true`: each slave is connected to a DPI RW bridge (bus_read/bus_write).
  * `sim=false`: expose per-device LiteBusRW ports for SoC integration.
  */
class NzeaTile(sim: Boolean)(implicit config: CoreConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width

  val core = Module(new nzea_core.Core)
  private val fabricUserWidth = core.io.ibus.userWidth.max(core.io.dbus.userWidth)

  val ibusRoToRw = Module(new LiteBusROToRW(addrWidth, dataWidth, core.io.ibus.userWidth))
  ibusRoToRw.io.in <> core.io.ibus
  val dbusUserAdapter = Module(new LiteBusRwUserWidthAdapter(
    addrWidth,
    dataWidth,
    core.io.dbus.userWidth,
    fabricUserWidth
  ))
  dbusUserAdapter.io.in <> core.io.dbus

  val status = IO(new TileStatusBundle)
  if (sim) {
    val ram = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth))
    val uart = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth))
    val finisher = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth))
    val clint = Module(new TileSimDeviceDpiBridge(addrWidth, dataWidth, fabricUserWidth))
    LiteBusRWCrossbar(TileAddressMap.ranges) { x =>
      x <> ibusRoToRw.io.out
      x <> dbusUserAdapter.io.out
      x <> ram.io.bus
      x <> uart.io.bus
      x <> finisher.io.bus
      x <> clint.io.bus
    }
    val cb = Module(new CommitDpiBridge)
    cb.io.commit_msg := core.io.commit_msg
    status.commit_msg_valid := core.io.commit_msg.valid
  } else {
    val devices = IO(new TileDeviceBusBundle(addrWidth, dataWidth, fabricUserWidth))
    val commit_msg = IO(Output(Valid(new CommitMsg)))

    val ramTap = Module(new TileSlaveTap(addrWidth, dataWidth, fabricUserWidth))
    val uartTap = Module(new TileSlaveTap(addrWidth, dataWidth, fabricUserWidth))
    val finisherTap = Module(new TileSlaveTap(addrWidth, dataWidth, fabricUserWidth))
    val clintTap = Module(new TileSlaveTap(addrWidth, dataWidth, fabricUserWidth))
    ramTap.io.ext <> devices.ram
    uartTap.io.ext <> devices.uart16550
    finisherTap.io.ext <> devices.sifive_test_finisher
    clintTap.io.ext <> devices.clint

    LiteBusRWCrossbar(TileAddressMap.ranges) { x =>
      x <> ibusRoToRw.io.out
      x <> dbusUserAdapter.io.out
      x <> ramTap.io.bus
      x <> uartTap.io.bus
      x <> finisherTap.io.bus
      x <> clintTap.io.bus
    }

    commit_msg := core.io.commit_msg
    status.commit_msg_valid := core.io.commit_msg.valid
  }
}
