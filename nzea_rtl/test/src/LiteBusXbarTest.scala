package nzea_rtl

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class LiteBusXbarTest extends AnyFreeSpec {
  private val ranges = Seq(
    LiteBusAddrRange(base = BigInt("00000000", 16), size = BigInt("00010000", 16)),
    LiteBusAddrRange(base = BigInt("10000000", 16), size = BigInt("00010000", 16))
  )
  "LiteBusRWXbar elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusRWXbar(32, 32, 8, ranges))
  }

  "LiteBusROXbar elaborates" in {
    ChiselStage.emitSystemVerilog(new LiteBusROXbar(32, 32, 8, ranges))
  }

  "LiteBusRWXbar emits decodeMiss path in FIRRTL" in {
    val fir = ChiselStage.emitCHIRRTL(new LiteBusRWXbar(32, 32, 8, ranges))
    assert(fir.contains("io_decodeMiss"), "decodeMiss output should be present")
    assert(fir.contains("io_out_0_req_valid"), "slave0 request valid should be emitted")
    assert(fir.contains("io_out_1_req_valid"), "slave1 request valid should be emitted")
  }

  "LiteBusRWXbar rejects out-of-range address windows" in {
    val bad = Seq(LiteBusAddrRange(base = BigInt("100000000", 16), size = 0x1000))
    assertThrows[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new LiteBusRWXbar(32, 32, 8, bad))
    }
  }
}
