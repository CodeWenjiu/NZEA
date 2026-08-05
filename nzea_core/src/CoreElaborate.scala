package nzea_core

import chisel3._
import chisel3.util.Valid
import _root_.circt.stage.ChiselStage
import nzea_core.config.CoreConfig

object CoreElaborate {

  /** Core wrapper: `config.sim=true` enables DPI bridges; else expose ibus/dbus/commit as top-level IO. */
  class Top(mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty)(implicit config: CoreConfig) extends Module {
    override def desiredName = "NzeaCore"

    private val addrWidth = config.width
    private val dataWidth = config.width

    val core = Module(new Core(mmioRanges))

    if (config.sim) {
      val ib = Module(
        new nzea_core.dpi.IbusDpiBridge(addrWidth, dataWidth, core.io.ibus.userWidth, core.io.ibus.idWidth)
      )
      val db = Module(
        new nzea_core.dpi.DbusDpiBridge(addrWidth, dataWidth, core.io.dbus.userWidth, core.io.dbus.idWidth)
      )
      val cb = Module(new nzea_core.dpi.CommitDpiBridge)
      core.io.ibus <> ib.io.bus
      core.io.dbus <> db.io.bus
      cb.io.commit_msg := core.io.commit_msg
    } else {
      val ibus = IO(chiselTypeOf(core.io.ibus))
      val dbus = IO(chiselTypeOf(core.io.dbus))
      val commit_msg = IO(Output(Valid(new retire.CommitMsg)))
      ibus <> core.io.ibus
      dbus <> core.io.dbus
      commit_msg := core.io.commit_msg
    }

  }

  def elaborate(
      outDir: String,
      firtoolOpts: Array[String],
      mmioRanges: Seq[(BigInt, BigInt)] = Seq.empty
  )(implicit config: CoreConfig): Unit = {
    println(
      s"Generating NzeaCore (isa: ${config.isa}, sim: ${config.sim})"
    )
    println(s"Output: $outDir")

    lazy val topModule = new Top(mmioRanges)
    ChiselStage.emitSystemVerilogFile(
      topModule,
      args = Array("--target-dir", outDir),
      firtoolOpts = firtoolOpts
    )
  }

}
