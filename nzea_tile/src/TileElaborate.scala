package nzea_tile

import _root_.circt.stage.ChiselStage
import chisel3._
import chisel3.util.Valid
import nzea_config.{CacheConfig, SynthPlatform}
import nzea_core.config.CoreConfig
import nzea_core.retire.CommitMsg
import nzea_rtl.{BootReq, FabricBusRW}

object TileElaborate {

  /** Tile IO bundle exposed at top level for simulation testbenches and FPGA board wrappers. */
  class TileTopIO(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
    val commit_msg = Output(Valid(new CommitMsg))
    val uart_txd = Output(Bool())
    val uart_rxd = Input(Bool())
    val finish = Output(Bool())
    // External RAM interface for board-level DDR3/SRAM adapter
    val extRamBus = new FabricBusRW(addrWidth, dataWidth, userWidth, idWidth)
    val extRamBoot = Output(Valid(new BootReq(15)))
    val extRamCalibDone = Input(Bool())
  }

  /** Tile wrapper: `sim=true` enables DPI bridges; else expose tile IO as top-level ports. */
  class Top(
      sim: Boolean,
      platform: SynthPlatform,
      clockHz: Int = 100_000_000,
      cache: Option[CacheConfig] = Some(CacheConfig())
  )(implicit config: CoreConfig)
      extends Module {
    override def desiredName = "NzeaTile"

    val tile = Module(new NzeaTile(sim, platform, clockHz, cache))
    private val addrWidth = config.width
    private val dataWidth = config.width
    private val fabricUserWidth = 64
    private val fabricIdWidth = 8
    val io = IO(new TileTopIO(addrWidth, dataWidth, fabricUserWidth, fabricIdWidth))

    if (sim) {
      tile.io := DontCare
      io := DontCare
    } else {
      io.commit_msg := tile.io.asInstanceOf[nzea_tile.platform.HasCommitMsg].commit_msg

      platform match {
        case SynthPlatform.Yosys =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.yosys.TileIo]
          val devices = IO(chiselTypeOf(io2.yosys_devices))
          devices <> io2.yosys_devices
          io.uart_txd := DontCare
          io.uart_rxd := DontCare
          io.finish := DontCare
          io.extRamBus := DontCare
          io.extRamBoot := DontCare
          io.extRamCalibDone := DontCare

        case SynthPlatform.Fpga =>
          val io2 = tile.io.asInstanceOf[nzea_tile.platform.fpga.TileIo]
          io.uart_txd := io2.fpga_uart.txd
          io2.fpga_uart.rxd := io.uart_rxd
          io2.fpga_uart.ctsn := false.B // tied low (active)
          io.finish := io2.fpga_finish
          // Pass-through extRamBus (both sides are master-side; manual directional connection)
          io.extRamBus.req.valid := io2.extRamBus.req.valid
          io.extRamBus.req.bits := io2.extRamBus.req.bits
          io2.extRamBus.req.ready := io.extRamBus.req.ready
          io2.extRamBus.req.flush := io.extRamBus.req.flush
          io2.extRamBus.resp.valid := io.extRamBus.resp.valid
          io2.extRamBus.resp.bits := io.extRamBus.resp.bits
          io.extRamBus.resp.ready := io2.extRamBus.resp.ready
          io.extRamBus.resp.flush := io2.extRamBus.resp.flush
          io.extRamBoot <> io2.extRamBoot
          io2.extRamCalibDone := io.extRamCalibDone
      }
    }

  }

  def elaborate(
      sim: Boolean,
      platform: SynthPlatform,
      outDir: String,
      clockHz: Int = 100_000_000,
      firtoolOpts: Array[String],
      cache: Option[CacheConfig] = Some(CacheConfig())
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaTile (isa: ${config.isa}, platform: ${platform.segment}, sim: $sim)"
    )
    println(s"Output: $outDir")

    ChiselStage.emitSystemVerilogFile(
      new Top(sim, platform, clockHz, cache),
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }

}
