package nzea_core.frontend

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

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
  private val E = IbusUser.epochBits

  "IbusUser" should "round-trip pc/pred/epoch" in {
    test(new IbusUserWrapper(W)) { dut =>
      val cases = Seq(
        (0x80000000L, 0x80000004L, 0),
        (0x80005d18L, 0x80005d1cL, 1),
        (0x80005d2cL, 0x80005d5cL, 3),
        (0x80005e98L, 0x80005e9cL, 7),
        (0x8fffffc0L, 0x8fffffc4L, 15)
      )
      for ((pc, pred, e) <- cases) {
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

  it should "distinguish epochs" in {
    test(new IbusUserWrapper(W)) { dut =>
      dut.io.pc.poke(0x80000000L)
      dut.io.pred.poke(0x80000004L)
      for (e <- 0 until (1 << E)) {
        dut.io.epoch.poke(e)
        dut.clock.step()
        dut.io.epochOut.expect(e)
      }
    }
  }

  it should "use correct user width" in {
    assert(IbusUser.userWidth(W) == W * 2 + E)
  }

}
