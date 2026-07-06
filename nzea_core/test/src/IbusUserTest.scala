package nzea_core.frontend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Tiny wrapper for testing IbusUser pack/extract. */
class IbusUserWrapper(val addrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val pc = Input(UInt(addrWidth.W))
    val pred = Input(UInt(addrWidth.W))
    val epoch = Input(UInt(IbusUser.epochBits.W))
    val pcOut = Output(UInt(addrWidth.W))
    val predOut = Output(UInt(addrWidth.W))
    val epochOut = Output(UInt(IbusUser.epochBits.W))
  })

  private val user = IbusUser.pack(addrWidth, io.pred, io.pc, io.epoch)
  io.pcOut := IbusUser.pc(addrWidth, user)
  io.predOut := IbusUser.predNextPc(addrWidth, user)
  io.epochOut := IbusUser.epoch(addrWidth, user)
}

class IbusUserTest extends AnyFlatSpec with ChiselScalatestTester {
  private val W = 32

  "IbusUser pack/unpack" should "round-trip at reset vector" in {
    test(new IbusUserWrapper(W)) { dut =>
      dut.io.pc.poke(0x80000000L)
      dut.io.pred.poke(0x80000004L)
      dut.io.epoch.poke(0)
      dut.clock.step()
      dut.io.pcOut.expect(0x80000000L)
      dut.io.predOut.expect(0x80000004L)
      dut.io.epochOut.expect(0)
    }
  }

  it should "round-trip with max epoch" in {
    test(new IbusUserWrapper(W)) { dut =>
      dut.io.pc.poke(0x80000000L)
      dut.io.pred.poke(0x80000004L)
      dut.io.epoch.poke(3)
      dut.clock.step()
      dut.io.pcOut.expect(0x80000000L)
      dut.io.predOut.expect(0x80000004L)
      dut.io.epochOut.expect(3)
    }
  }

  it should "round-trip at various addresses" in {
    test(new IbusUserWrapper(W)) { dut =>
      val cases = Seq(
        (0x80000000L, 0x80000004L),
        (0x80005d18L, 0x80005d1cL),
        (0x80005e98L, 0x80005e9cL),
        (0x8fffffc0L, 0x8fffffc4L)
      )
      for (((pc, pred), e) <- cases.zipWithIndex) {
        dut.io.pc.poke(pc)
        dut.io.pred.poke(pred)
        dut.io.epoch.poke(e)
        dut.clock.step()
        dut.io.pcOut.expect(pc)
        dut.io.predOut.expect(pred)
        dut.io.epochOut.expect(e)
      }
    }
  }

  it should "round-trip with branch targets" in {
    test(new IbusUserWrapper(W)) { dut =>
      dut.io.pc.poke(0x80005d2cL)
      dut.io.pred.poke(0x80005d5cL)
      dut.io.epoch.poke(3)
      dut.clock.step()
      dut.io.pcOut.expect(0x80005d2cL)
      dut.io.predOut.expect(0x80005d5cL)
      dut.io.epochOut.expect(3)
    }
  }

  it should "distinguish epochs" in {
    test(new IbusUserWrapper(W)) { dut =>
      dut.io.pc.poke(0x80000000L)
      dut.io.pred.poke(0x80000004L)
      dut.io.epoch.poke(1)
      dut.clock.step()
      dut.io.epochOut.expect(1)
      dut.io.epoch.poke(2)
      dut.clock.step()
      dut.io.epochOut.expect(2)
    }
  }

}
