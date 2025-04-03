package config

import chisel3._
import freechips.rocketchip.diplomacy.AddressSet

object Elaboratenpc extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make verilator happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "locationInfoStyle=wrapInAtSquareBracket",
      "disallowLocalVariables",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(true)
  Config.setIcacheParam(AddressSet.misaligned(0x80000000L, 0x8000000), 2, 2, 16)
  Config.setDiffMisMap( AddressSet.misaligned(0x10000000, 0x1000) ++
                        AddressSet.misaligned(0xa0000048L, 0x10))

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_cpu.top(), args, firtoolOptions)
}
