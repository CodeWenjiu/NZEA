package nzea_core.backend.vector

import _root_.circt.stage.ChiselStage
import nzea_config.core.BpuConfig
import nzea_config.core.CoreConfig
import org.scalatest.funsuite.AnyFunSuite

class VectorBackendTest extends AnyFunSuite {

  test("VectorBackend elaborates") {
    implicit val config: CoreConfig = CoreConfig(
      isa = "riscv32im_zve32x_zvl128b",
      defaultPc = 0x8000_0000L,
      robDepth = 16,
      issueQueueDepth = 4,
      prfDepth = 64,
      vlen = 128,
      vrfDepth = 64,
      viqDepth = 8,
      bpu = BpuConfig.typical,
      sim = false
    )
    ChiselStage.emitCHIRRTL(new VectorBackend(robIdWidth = 4))
  }

}
