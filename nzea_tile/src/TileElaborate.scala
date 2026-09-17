package nzea_tile

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util.Valid
import nzea_config.PlatformConfig
import nzea_config.SynthPlatform
import nzea_config.tile.TileConfig
import nzea_config.core.CoreConfig
import nzea_core.retire.CommitMsg
import nzea_rtl.{BootReq, LiteBusRW}

object TileElaborate {

  /** Tile IO bundle exposed at top level for simulation testbenches and FPGA board wrappers.
    *
    * `numExtMem` is `Some(n)` on the fpga platform (n external-memory bus ports) and `None` on yosys — whose top level
    * connects the fixed `devices` port instead, so the extRam* members are absent there rather than DontCare stubs.
    */
  class TileTopIO(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int, numExtMem: Option[Int])(implicit
      config: CoreConfig
  ) extends Bundle {
    val commit_msg = Output(Valid(new CommitMsg))
    val uart_txd = Output(Bool())
    val uart_rxd = Input(Bool())
    val finish = Output(Bool())
    // External memory buses (bus 0 = boot RAM with the boot/calib companions;
    // additional buses are plain memory regions, e.g. a DDR3 device adapter).
    val extRamBus = numExtMem.map(n => Vec(n, new LiteBusRW(addrWidth, dataWidth, userWidth, idWidth)))
    val extRamBoot = numExtMem.map(_ => Output(Valid(new BootReq(15))))
    val extRamCalibDone = numExtMem.map(_ => Input(Bool()))
  }

  /** Tile wrapper: `sim=true` enables DPI bridges; else expose tile IO as top-level ports. */
  class Top(cfg: TileConfig)(implicit config: CoreConfig) extends Module {
    override def desiredName = "NzeaTile"

    val tile = Module(new NzeaTile(cfg))
    private val addrWidth = config.width
    private val dataWidth = config.width

    // Compute to match NzeaTile's internal fabricUserWidth exactly.
    private val fabricUserWidth = NzeaTile.fabricUserWidth

    private val fabricIdWidth = 8

    private val numExtMem = cfg.platform match {
      case PlatformConfig.Fpga(n) => Some(n)
      case PlatformConfig.Yosys   => None
    }

    val io = IO(new TileTopIO(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth, numExtMem))

    if (config.sim) {
      tile.io := DontCare
      io := DontCare
    } else {
      io.commit_msg := tile.io.asInstanceOf[nzea_tile.platform.HasCommitMsg].commit_msg

      cfg.synthPlatform match {
        case SynthPlatform.Yosys =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.yosys.TileIo]
          val devices = IO(chiselTypeOf(io2.yosys_devices))
          devices <> io2.yosys_devices
          io.uart_txd := DontCare
          io.uart_rxd := DontCare
          io.finish := DontCare

        case SynthPlatform.Fpga =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.fpga.TileIo]
          io.uart_txd := io2.fpga_uart.txd
          io2.fpga_uart.rxd := io.uart_rxd
          io2.fpga_uart.ctsn := false.B // tied low (active)
          io.finish := io2.fpga_finish
          // Pass-through the external memory buses (both sides are master-side;
          // manual directional connection, <> would clash). The Option members
          // are always Some on the fpga platform.
          val topExtRam = io.extRamBus.get
          for (i <- 0 until topExtRam.length) {
            topExtRam(i).req.valid := io2.extRamBus(i).req.valid
            topExtRam(i).req.bits := io2.extRamBus(i).req.bits
            io2.extRamBus(i).req.ready := topExtRam(i).req.ready
            io2.extRamBus(i).req.flush := topExtRam(i).req.flush
            io2.extRamBus(i).resp.valid := topExtRam(i).resp.valid
            io2.extRamBus(i).resp.bits := topExtRam(i).resp.bits
            topExtRam(i).resp.ready := io2.extRamBus(i).resp.ready
            topExtRam(i).resp.flush := io2.extRamBus(i).resp.flush
          }
          io.extRamBoot.get <> io2.extRamBoot
          io2.extRamCalibDone := io.extRamCalibDone.get
      }
    }

  }

  def elaborate(
      cfg: TileConfig,
      outDir: String
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaTile (isa: ${config.isa}, platform: ${cfg.synthPlatform.segment}, sim: ${config.sim})"
    )
    println(s"Output: $outDir")

    ChiselStage.emitSystemVerilogFile(
      new Top(cfg),
      args = Array("--target-dir", outDir),
      firtoolOpts = cfg.synthPlatform.firtoolOpts(config.sim)
    )
  }

}
