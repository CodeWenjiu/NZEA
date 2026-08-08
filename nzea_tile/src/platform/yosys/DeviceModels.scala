package nzea_tile.platform.yosys

import _root_.circt.stage.ChiselStage
import chisel3._
import nzea_config.core.CoreConfig
import nzea_device.clint.Clint
import nzea_device.finisher.SifiveTestFinisher
import nzea_device.ram.RamFabricSlave
import nzea_device.uart.FabricBusUart
import nzea_tile.NzeaTile

/** Standard RTL stand-ins for the outer devices of the yosys platform.
  *
  * The yosys tile exposes RAM/UART16550/CLINT/finisher as fabric ports — the real chips live on the board, outside the
  * FPGA. These stand-ins are part of the platform delivery: any RTL simulation (e.g. the iverilog TB) assembles the
  * full system from the same device models, so the simulated environment matches the board-level chip set.
  *
  * Scope boundary: this emits stand-ins only for devices we own in RTL. Platform-provided IP (e.g. Vivado MIG/MMCM)
  * must use the vendor's official simulation files instead — never hand-written models here.
  */
object DeviceModels {

  def emit(outDir: String, clockHz: Int)(implicit config: CoreConfig): Unit = {
    println("Generating yosys platform outer-device stand-ins (ram/uart16550/clint/finisher)")
    val firtoolOpts = Array("--lowering-options=disallowLocalVariables", "-disable-all-randomization")
    val w = config.width
    val userW = NzeaTile.fabricUserWidth
    val idW = NzeaTile.fabricIdWidth
    val map = AddressMap
    Seq[() => chisel3.Module](
      () => new RamFabricSlave(w, w, userW, idW, map.ram.base),
      () => new FabricBusUart(map.uart16550.base, clockHz, 115200, userW, idW),
      () => new Clint(map.clint.base, userW, idW),
      () => new SifiveTestFinisher(w, w, userW, idW)
    ).foreach { mk =>
      ChiselStage.emitSystemVerilogFile(
        mk(),
        args = Array("--target-dir", outDir),
        firtoolOpts = firtoolOpts
      )
    }
  }

}
