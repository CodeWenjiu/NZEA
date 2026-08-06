package nzea_core

import _root_.circt.stage.ChiselStage
import nzea_core.backend.vector.VectorBackend
import nzea_core.config.{BpuConfig, CoreConfig}
import org.scalatest.funsuite.AnyFunSuite

class VectorBackendTest extends AnyFunSuite {
  test("VectorBackend elaborates") {
    implicit val config: CoreConfig = CoreConfig(
      isa = "riscv32im_zve32x_zvl128b",
      bpu = BpuConfig(64, 16, Some(8))
    )
    ChiselStage.emitCHIRRTL(new VectorBackend(robIdWidth = 4))
  }
}
