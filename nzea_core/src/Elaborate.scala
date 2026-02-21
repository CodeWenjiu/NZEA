package nzea_core

// _root_ disambiguates from package chisel3.util.circt if user imports chisel3.util._
import _root_.circt.stage.ChiselStage

object Elaborate {
  def elaborate() = {
    println("Generate Nzea Core")

    ChiselStage.emitSystemVerilogFile(
      new GCD,
      args = Array(
        "--target-dir",
        "build"
      ),
      firtoolOpts = Array(
        "-disable-all-randomization",
        "-strip-debug-info",
        "-default-layer-specialization=enable"
      )
    )
  }


  def main(args: Array[String]): Unit = {
    elaborate()
  }
}
