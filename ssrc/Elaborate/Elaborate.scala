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
  Config.setIcacheParam(AddressSet.misaligned(0x80000000L, 0x8000000), 4, 1, 16)
  Config.setDcacheParam(AddressSet.misaligned(0x10000000, 0x1000), 4, 1, 16)
  Config.setDiffMisMap( AddressSet.misaligned(0x10000000, 0x1000) ++
                        AddressSet.misaligned(0xa0000048L, 0x10))

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_soc.platform.npc.top(), args, firtoolOptions)
}

object Elaborateysyxsoc extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      "locationInfoStyle=wrapInAtSquareBracket",
      "disallowLocalVariables",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h30000000".U(32.W)
  Config.setSimulate(true)
  Config.setIcacheParam(AddressSet.misaligned(0xa0000000L, 0x2000000), 4, 1, 16)
  Config.setDiffMisMap( AddressSet.misaligned(0x10000000, 0x1000) ++
                        AddressSet.misaligned(0x10002000, 0x10) ++
                        AddressSet.misaligned(0x10011000, 0x8) ++
                        AddressSet.misaligned(0x02000000L, 0x10000))

  circt.stage.ChiselStage.emitSystemVerilogFile(gen = new riscv_soc.platform.ysyxsoc.ysyx_23060198(), args = args, firtoolOpts  = firtoolOptions)
}

object Elaborateysyxsoc_core extends App {
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
  Config.setIcacheParam(AddressSet.misaligned(0xa0000000L, 0x2000000), 4, 1, 16)

  circt.stage.ChiselStage.emitSystemVerilogFile(gen = new riscv_soc.platform.ysyxsoc.top(), args = args, firtoolOpts  = firtoolOptions)
}

object Elaboratejyd extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make vivado happy
      "mitigateVivadoArrayIndexConstPropBug",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(true)
  Config.setIcacheParam(AddressSet.misaligned(0x80000000L, 0x8000000), 8, 2, 16)
  Config.setDiffMisMap(AddressSet.misaligned(0x80200000L, 0x10000))

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_soc.platform.jyd.onboard.top(), args, firtoolOptions)
}

object Elaboratejyd_core extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make vivado happy
      "mitigateVivadoArrayIndexConstPropBug",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(false)
  Config.setIcacheParam(AddressSet.misaligned(0x80000000L, 0x8000000), 8, 2, 16)
  Config.setDiffMisMap(AddressSet.misaligned(0x80200000L, 0x10000))

  Config.setFourStateSim(true)
  Config.setRegFix(false)

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_soc.platform.jyd.onboard.core(), args, firtoolOptions)
}

object Elaboratejydremote extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make vivado happy
      "mitigateVivadoArrayIndexConstPropBug",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(true)
  Config.setDiffMisMap(AddressSet.misaligned(0x80140000L, 0x100000))

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_soc.platform.jyd.remote.top(), args, firtoolOptions)
}

object Elaboratejydremote_core extends App {
  val firtoolOptions = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "--lowering-options=" + List(
      // make vivado happy
      "mitigateVivadoArrayIndexConstPropBug",
    ).reduce(_ + "," + _)
  )
  
  Config.Reset_Vector = "h80000000".U(32.W)
  Config.setSimulate(false)
  Config.setDiffMisMap(AddressSet.misaligned(0x80140000L, 0x100000))
  Config.setRegFix(false)

  circt.stage.ChiselStage.emitSystemVerilogFile(new riscv_soc.platform.jyd.remote.top(), args, firtoolOptions)
}
