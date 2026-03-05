package nzea_core

import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

/** DbusMemBridge and DbusDpiBridge pipeline test.
  * The bridge uses internalResp.ready directly (like IbusDpiBridge) to allow
  * continuous pipelined req acceptance. MemUnit was updated to allow 2 in-flight
  * requests (pipelineDepth=2) to match the bridge pipeline depth. */
class DbusMemBridgeTest extends AnyFreeSpec {
  "DbusMemBridge elaborates" in {
    ChiselStage.emitSystemVerilog(new nzea_core.dpi.DbusMemBridge(32, 32, 0))
  }

  "DbusDpiBridge elaborates" in {
    ChiselStage.emitSystemVerilog(new nzea_core.dpi.DbusDpiBridge(32, 32, 0))
  }
}
