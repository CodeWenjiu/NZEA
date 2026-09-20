package nzea_tile.platform

import chisel3._
import chiseltest._
import java.nio.file.{Files, Path}
import nzea_device.uart.FabricBusUart
import org.scalatest.flatspec.AnyFlatSpec

/** BootFsm + UART boot-path integration test.
  *
  * Drives real 8N1 serial bytes into [[FabricBusUart]] and checks the full uart-reset command (magic + addr + size = 0)
  * releases the CPU reset. This guards the reset-domain invariant in `fpga/Platform.scala`: the UART must stay alive
  * (bootReset domain) while the BootFsm is receiving a load, even though the CPU itself is held in reset during the
  * sequence.
  *
  * Complements [[BootFsmTest]], which pokes `rx_valid`/`rx_data` directly and so cannot see the UART at all: the
  * reset-domain bug only exists once real serial decoding is in the loop.
  *
  * A negative control (UART back in the cpuReset domain) pins down that the positive case is sensitive to the domain
  * choice rather than passing for an unrelated reason.
  */
class BootUartTest extends AnyFlatSpec with ChiselScalatestTester {

  private val MAGIC = 0xb007b007
  private val RAM_DEPTH = 8
  // bitDiv = 100: UartRx samples every (bitDiv + 1) cycles (one-cycle drift per bit),
  // so a tiny bitDiv (e.g. 10) makes the drift cross bit boundaries and the decoder
  // fails even though the wiring is fine. At real ratios (868) the drift is negligible.
  private val CLOCK_HZ = 11520000
  private val BAUD = 115200
  private val BIT_DIV = CLOCK_HZ / BAUD // cycles per serial bit

  private def writeHex(path: Path, words: Seq[Int]): Unit = {
    val text = words.map(w => f"$w%08x").mkString("\n") + "\n"
    Files.writeString(path, text)
  }

  /** Mirrors the boot-path wiring of `fpga/Platform.scala` (keep in sync!): bootFsm in `bootReset`, UART in `cpuReset`,
    * rx stream bridged across. The UART domain here is the one under test.
    */
  private class BootPathHarness(uartInCpuResetDomain: Boolean, hexPath: String) extends Module {

    val io = IO(new Bundle {
      val rxd = Input(Bool())
      val extRamCalibDone = Input(Bool())
      val tileReset = Input(Bool())
      val cpuReset = Output(Bool())
    })

    val bootReset = io.tileReset || !io.extRamCalibDone
    val cpuReset = Wire(Bool())
    val bootFsm = withReset(bootReset) { Module(new BootFsm(RAM_DEPTH, hexPath)) }
    cpuReset := bootReset || bootFsm.io.cpu_reset

    val uart = if (uartInCpuResetDomain) {
      withReset(cpuReset) {
        Module(new FabricBusUart(0x10000000L, CLOCK_HZ, BAUD, 8, 8))
      }
    } else {
      withReset(bootReset) {
        Module(new FabricBusUart(0x10000000L, CLOCK_HZ, BAUD, 8, 8))
      }
    }

    uart.io.rxd := io.rxd
    uart.io.ctsn := false.B
    // Master-side drive only; req.ready/resp.{valid,bits} are driven by the uart.
    uart.io.bus.req.valid := false.B
    uart.io.bus.req.bits := DontCare
    uart.io.bus.resp.ready := false.B

    bootFsm.io.boot_en := true.B
    bootFsm.io.rx_valid := uart.io.boot_rx_valid
    bootFsm.io.rx_data := uart.io.boot_rx_data
    io.cpuReset := cpuReset
  }

  /** One 8N1 byte on rxd (start, LSB-first data, stop), BIT_DIV cycles per bit. */
  private def sendByte(dut: BootPathHarness, b: Int): Unit = {
    val bits = 0 +: (0 until 8).map(i => (b >> i) & 1) :+ 1
    for (bit <- bits) {
      dut.io.rxd.poke((bit == 1).B)
      dut.clock.step(BIT_DIV)
    }
  }

  private def sendWord(dut: BootPathHarness, w: Int): Unit = {
    for (i <- 0 until 4) sendByte(dut, (w >> (8 * i)) & 0xff)
  }

  private def withDut(uartInCpuResetDomain: Boolean)(f: BootPathHarness => Unit): Unit = {
    val hex = Files.createTempFile("bootuart", ".hex")
    writeHex(hex, Seq(0x00000013, 0x00000013, 0x00000013))
    test(new BootPathHarness(uartInCpuResetDomain, hex.toString)) { dut => f(dut) }
  }

  /** Power on, calibrate, let the mrom copy + pad finish, return to idle. */
  private def bootToIdle(dut: BootPathHarness): Unit = {
    dut.io.tileReset.poke(true.B)
    dut.io.extRamCalibDone.poke(true.B)
    dut.io.rxd.poke(true.B)
    dut.clock.step(2)
    dut.io.tileReset.poke(false.B)
    for (_ <- 0 until RAM_DEPTH * 4) dut.clock.step()
    assert(!dut.io.cpuReset.peek().litToBoolean, "cpu must leave reset after boot")
  }

  /** Drive a complete uart-reset command (magic + addr + size = 0) and report whether cpu_reset was released
    * afterwards.
    */
  private def runUartResetCommand(dut: BootPathHarness): Boolean = {
    sendWord(dut, MAGIC)
    var t = 0
    while (!dut.io.cpuReset.peek().litToBoolean && t < 2000) { dut.clock.step(); t += 1 }
    assert(dut.io.cpuReset.peek().litToBoolean, "magic not recognized, cpu_reset never asserted")

    sendWord(dut, 0) // addr
    sendWord(dut, 0) // size = 0 -> reset-only command

    var s = 0
    while (dut.io.cpuReset.peek().litToBoolean && s < 4000) { dut.clock.step(); s += 1 }
    !dut.io.cpuReset.peek().litToBoolean
  }

  "the boot path" should "keep the UART alive through a uart-reset command" in {
    withDut(uartInCpuResetDomain = false) { dut =>
      bootToIdle(dut)
      assert(
        runUartResetCommand(dut),
        "cpu_reset stuck high after size=0: UART was reset during the load sequence"
      )
    }
  }

  it should "lose the command with the UART in the cpuReset domain (negative control)" in {
    // The magic still lands (cpu_reset is low while it is being received), but
    // BootFsm then holds cpu_reset high through sAddr/sSize/sData, which resets
    // the UART RX path mid-decode: the addr/size words never arrive and the FSM
    // never completes. This is exactly the bug the bootReset domain fixes, so if
    // this case ever starts passing, the positive case above has stopped being
    // evidence about the reset domain.
    withDut(uartInCpuResetDomain = true) { dut =>
      bootToIdle(dut)
      assert(
        !runUartResetCommand(dut),
        "uart-reset completed with the UART in the cpuReset domain - the negative " +
          "control no longer reproduces the bug, so the positive case is no longer " +
          "sensitive to the reset-domain wiring"
      )
    }
  }

}
