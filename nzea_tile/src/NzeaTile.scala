package nzea_tile

import chisel3._
import nzea_config.{CacheConfig, SynthPlatform}
import nzea_tile.TileConfig
import nzea_core.config.CoreConfig
import nzea_core.dpi.CommitDpiBridge
import nzea_rtl.{LiteAddrRange, LiteBusCrossbar, LiteBusRegisterSlice, LiteBusRW, LiteBusWidthConverter}
import nzea_tile.platform.fpga
import nzea_tile.platform.yosys
import nzea_tile.platform.HasCommitMsg
import nzea_cache.SetAssoc

/** Tile address map dispatch. Platform-specific ranges defined in their respective packages. */
object TileAddressMap {

  def forPlatform(platform: SynthPlatform): Seq[LiteAddrRange] = platform match {
    case SynthPlatform.Yosys => yosys.AddressMap.ranges
    case SynthPlatform.Fpga  => fpga.AddressMap.ranges
  }

}

/** CPU tile: [[nzea_core.Core]] plus FabricBus interconnect and per-device external interfaces. Bus fabric: 2 masters
  * (core ibus+dbus) and platform-selected slaves. `sim=true`: slaves are connected to DPI bridges (bus_read/bus_write).
  * `sim=false`: exposes platform-specific HW IO as top-level ports.
  */
class NzeaTile(cfg: TileConfig)(implicit
    config: CoreConfig
) extends Module {
  private val sim = cfg.sim
  private val platform = cfg.synthPlatform
  private val clockHz = cfg.clockHz
  private val cache = cfg.cache
  private val perSlaveOutstanding = cfg.perSlaveOutstanding
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ranges = TileAddressMap.forPlatform(platform)
  // fabricUserWidth covers ibus + dbus user payloads.
  // Computed from config (no forward-reference to `core`).
  private val ibusUserW = nzea_core.frontend.IbusUser.userWidth(config.width)

  private val dbusUserW = {
    val rw = chisel3.util.log2Ceil(config.robDepth.max(2))
    val pw = config.prfAddrWidth
    val upw = rw + nzea_core.backend.integer.LsuOp.getWidth + 2 + pw + 1 // +1 for is_mmio
    config.width.max(upw)
  }

  private val fabricUserWidth = ibusUserW.max(dbusUserW)
  private val fabricIdWidth = 8

  val io = IO(platform match {
    case SynthPlatform.Yosys => new yosys.TileIo(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
    case SynthPlatform.Fpga  => new fpga.TileIo(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth)
  })

  io := DontCare

  val cpuReset = Wire(Bool())

  // Extract MMIO ranges (exclude RAM at 0x80000000) for the core's is_mmio detection.
  private val mmioRanges: Seq[(BigInt, BigInt)] = ranges.collect {
    case r if r.base != BigInt("80000000", 16) => (r.base, r.size)
  }

  val core = withReset(cpuReset) { Module(new nzea_core.Core(mmioRanges)) }

  // IBUS: register slice between IFU and crossbar.
  val ibusSlice = Module(
    new LiteBusRegisterSlice(addrWidth, dataWidth, core.io.ibus.userWidth, core.io.ibus.idWidth)
  )

  cache match {
    case Some(cfg) =>
      val icache = Module(
        new SetAssoc(
          nSets = cfg.nSets,
          nWays = cfg.nWays,
          lineBits = cfg.lineBits,
          addrWidth = addrWidth,
          dataWidth = dataWidth,
          userWidth = core.io.ibus.userWidth
        )
      )
      icache.io.top <> core.io.ibus

      if (cfg.lineBits == dataWidth) {
        ibusSlice.io.in <> icache.io.bottom
      } else {
        val iwidth = Module(
          new LiteBusWidthConverter(
            wideDataWidth = cfg.lineBits,
            narrowDataWidth = dataWidth,
            addrWidth = addrWidth,
            userWidth = core.io.ibus.userWidth,
            idWidth = 1
          )
        )
        iwidth.io.wide <> icache.io.bottom
        ibusSlice.io.in <> iwidth.io.narrow
      }

    case None =>
      ibusSlice.io.in <> core.io.ibus
  }

  // DBUS: register slice between LSU and crossbar.
  val dbusSlice = Module(
    new LiteBusRegisterSlice(addrWidth, dataWidth, core.io.dbus.userWidth, core.io.dbus.idWidth)
  )

  dbusSlice.io.in <> core.io.dbus

  val fabric = Module(
    new LiteBusCrossbar(
      numMasters = 2,
      addrWidth = addrWidth,
      dataWidth = dataWidth,
      userWidth = fabricUserWidth,
      idWidth = fabricIdWidth,
      ranges = ranges,
      perSlaveOutstanding = perSlaveOutstanding
    )
  )

  fabric.io.in(0) <> ibusSlice.io.out
  fabric.io.in(1) <> dbusSlice.io.out

  if (sim) {
    val cb = Module(new CommitDpiBridge)
    cb.io.commit_msg := core.io.commit_msg
  } else {
    io.asInstanceOf[HasCommitMsg].commit_msg := core.io.commit_msg
  }

  platform match {
    case SynthPlatform.Yosys =>
      yosys.Platform.connectDevices(
        core,
        fabric,
        io.asInstanceOf[yosys.TileIo],
        cpuReset,
        reset.asBool,
        sim,
        addrWidth,
        dataWidth,
        fabricUserWidth,
        fabricIdWidth,
        clockHz
      )
    case SynthPlatform.Fpga =>
      fpga.Platform.connectDevices(
        core,
        fabric,
        io.asInstanceOf[fpga.TileIo],
        cpuReset,
        reset.asBool,
        sim,
        addrWidth,
        dataWidth,
        fabricUserWidth,
        fabricIdWidth,
        clockHz
      )
  }

}
