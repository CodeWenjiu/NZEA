package config

import chisel3._
import freechips.rocketchip.diplomacy.AddressSet

object Elaborateysyxsoc extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h30000000".U(32.W)
  Config.setSimulate(true)
  Config.setIcacheParam(AddressSet.misaligned(0xa0000000L, 0x2000000), 2, 2, 16)
  Config.setDiffMisMap( AddressSet.misaligned(0x10000000, 0x1000) ++
                        AddressSet.misaligned(0x10002000, 0x10) ++
                        AddressSet.misaligned(0x10011000, 0x8) ++
                        AddressSet.misaligned(0x02000000L, 0x10000))

  circt.stage.ChiselStage.emitSystemVerilogFile(gen = new riscv_cpu.ysyx_23060198(), args = args, firtoolOpts  = firtoolOptions)
}

object Elaboratenpc extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(true)
  Config.setIcacheParam(AddressSet.misaligned(0x80000000L, 0x8000000), 2, 2, 16)
  Config.setDiffMisMap( AddressSet.misaligned(0x10000000, 0x1000) ++
                        AddressSet.misaligned(0xa0000048L, 0x10))

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_cpu.top(), args, firtoolOptions)
}

object Elaboratecore extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(false)
  Config.setIcacheParam(AddressSet.misaligned(0xa0000000L, 0x2000000), 2, 2, 16)

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_cpu.ysyx_23060198(), args, firtoolOptions)
}

// object ElaborateBASYS extends App {
//   val firtoolOptions = Array(
//     "-disable-all-randomization",
//     "-strip-debug-info",
//     "--lowering-options=" + List(
//       // make vivado happy
//       // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
//       "mitigateVivadoArrayIndexConstPropBug",
//       "locationInfoStyle=wrapInAtSquareBracket"
//     ).reduce(_ + "," + _)
//   )
//   circt.stage.ChiselStage.emitSystemVerilogFile(new BASYS.II_final(), args, firtoolOptions)
// }

// object ElaborateTangnano extends App {
//   val firtoolOptions = Array(
//     "-disable-all-randomization",
//     "-strip-debug-info",
//   )
//   circt.stage.ChiselStage.emitSystemVerilogFile(new ssrc.Tangnano.top, args, firtoolOptions)
// }

// object ElaborateZyqn extends App {
//   val firtoolOptions = Array(
//     "-disable-all-randomization",
//     "-strip-debug-info",
//     "--lowering-options=" + List(
//       // make vivado happy
//       // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
//       "mitigateVivadoArrayIndexConstPropBug",
//       "locationInfoStyle=wrapInAtSquareBracket"
//     ).reduce(_ + "," + _)
//   )
  
//   Config.Reset_Vector = "h80000000".U(32.W)
//   Config.setSimulate(false)

//   circt.stage.ChiselStage.emitSystemVerilogFile(new ssrc.Zyqn.top, args, firtoolOptions)
// }
