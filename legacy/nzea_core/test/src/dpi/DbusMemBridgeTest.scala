package nzea_core.dpi

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

/** DbusMemBridge and DbusDpiBridge pipeline test. The bridge uses internalResp.ready directly (like IbusDpiBridge) to
  * allow continuous pipelined req acceptance. MemUnit was updated to allow 2 in-flight requests (pipelineDepth=2) to
  * match the bridge pipeline depth.
  */
class DbusMemBridgeTest extends AnyFreeSpec {

  "DbusMemBridge elaborates" in {
    ChiselStage.emitSystemVerilog(new DbusMemBridge(32, 32, 1))
  }

  "DbusDpiBridge elaborates" in {
    ChiselStage.emitSystemVerilog(new DbusDpiBridge(32, 32, 1, 1))
  }

}
