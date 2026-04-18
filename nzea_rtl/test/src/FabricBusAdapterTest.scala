package nzea_rtl

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import circt.stage.ChiselStage
import org.scalatest.freespec.AnyFreeSpec

class FabricBusAdapterTest extends AnyFreeSpec with ChiselSim {
  "Lite/Fabric adapters elaborate" in {
    ChiselStage.emitSystemVerilog(new LiteBusROToFabricRW(32, 32, 8, 8, 4))
    ChiselStage.emitSystemVerilog(new LiteBusRWToFabricRW(32, 32, 8, 8, 4))
    ChiselStage.emitSystemVerilog(new FabricRWToLiteRW(32, 32, 8, 4, 8))
  }

  "LiteBusROToFabricRW converts request and propagates response/id sequence" in {
    simulate(new LiteBusROToFabricRW(32, 32, 8, 8, 4)) { dut =>
      dut.io.in.req.valid.poke(true.B)
      dut.io.in.req.bits.addr.poke("h00000100".U)
      dut.io.in.req.bits.user.poke("h7a".U)
      dut.io.in.resp.ready.poke(true.B)
      dut.io.in.resp.flush.poke(false.B)

      dut.io.out.req.ready.poke(true.B)
      dut.io.out.req.valid.expect(true.B)
      dut.io.out.req.bits.addr.expect("h00000100".U)
      dut.io.out.req.bits.user.expect("h7a".U)
      dut.io.out.req.bits.wen.expect(false.B)
      dut.io.out.req.bits.wdata.expect(0.U)
      dut.io.out.req.bits.wstrb.expect(0.U)
      dut.io.out.req.bits.id.expect(0.U)

      dut.io.out.resp.valid.poke(true.B)
      dut.io.out.resp.bits.data.poke("habcd1234".U)
      dut.io.out.resp.bits.user.poke("h7a".U)
      dut.io.out.resp.bits.id.poke(0.U)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.in.resp.bits.data.expect("habcd1234".U)
      dut.io.in.resp.bits.user.expect("h7a".U)

      dut.clock.step()
      dut.io.out.req.bits.id.expect(1.U)
    }
  }

  "FabricRWToLiteRW restores response id by request order and preserves data/user" in {
    simulate(new FabricRWToLiteRW(32, 32, 8, 4, 8)) { dut =>
      // req#1
      dut.io.in.req.valid.poke(true.B)
      dut.io.in.req.bits.addr.poke("h10000020".U)
      dut.io.in.req.bits.wen.poke(true.B)
      dut.io.in.req.bits.wdata.poke("h5555aaaa".U)
      dut.io.in.req.bits.wstrb.poke("hf".U)
      dut.io.in.req.bits.user.poke("h3c".U)
      dut.io.in.req.bits.id.poke("h9".U)
      dut.io.in.resp.ready.poke(true.B)
      dut.io.in.resp.flush.poke(false.B)

      dut.io.out.req.ready.poke(true.B)
      dut.io.out.req.valid.expect(true.B)
      dut.io.out.req.bits.addr.expect("h10000020".U)
      dut.io.out.req.bits.wen.expect(true.B)
      dut.io.out.req.bits.wdata.expect("h5555aaaa".U)
      dut.io.out.req.bits.wstrb.expect("hf".U)
      dut.io.out.req.bits.user.expect("h3c".U)
      dut.clock.step()

      // req#2
      dut.io.in.req.bits.addr.poke("h10000024".U)
      dut.io.in.req.bits.wen.poke(false.B)
      dut.io.in.req.bits.wdata.poke(0.U)
      dut.io.in.req.bits.wstrb.poke(0.U)
      dut.io.in.req.bits.user.poke("h2a".U)
      dut.io.in.req.bits.id.poke("h4".U)
      dut.io.out.req.valid.expect(true.B)
      dut.io.out.req.bits.addr.expect("h10000024".U)
      dut.io.out.req.bits.user.expect("h2a".U)
      dut.clock.step()
      dut.io.in.req.valid.poke(false.B)

      // resp#1 -> should carry id#1 (0x9)
      dut.io.out.resp.valid.poke(true.B)
      dut.io.out.resp.bits.data.poke("hcafebabe".U)
      dut.io.out.resp.bits.user.poke("h3c".U)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.in.resp.bits.data.expect("hcafebabe".U)
      dut.io.in.resp.bits.user.expect("h3c".U)
      dut.io.in.resp.bits.id.expect("h9".U)
      dut.clock.step()

      // resp#2 -> should carry id#2 (0x4)
      dut.io.out.resp.bits.data.poke("h12345678".U)
      dut.io.out.resp.bits.user.poke("h2a".U)
      dut.io.in.resp.valid.expect(true.B)
      dut.io.in.resp.bits.data.expect("h12345678".U)
      dut.io.in.resp.bits.user.expect("h2a".U)
      dut.io.in.resp.bits.id.expect("h4".U)
    }
  }
}
