package nzea_core

import _root_.circt.stage.ChiselStage
import nzea_core.backend.vector.VectorBackend
import nzea_config.NzeaConfig
import org.scalatest.funsuite.AnyFunSuite

class VectorBackendTest extends AnyFunSuite {
  test("VectorBackend elaborates") {
    implicit val config: NzeaConfig = NzeaConfig(isa = "riscv32im_zve32x_zvl128b")
    ChiselStage.emitCHIRRTL(new VectorBackend(robIdWidth = 4))
  }
}
