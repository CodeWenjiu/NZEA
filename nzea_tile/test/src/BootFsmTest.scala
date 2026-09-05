package nzea_tile.platform

import chisel3._
import chiseltest._
import java.nio.file.{Files, Path}
import org.scalatest.flatspec.AnyFlatSpec

/** BootFsm protocol tests.
  *
  * Covers the boot-from-mrom image, the normal load path, the `size = 0` reset-only
  * command (no RAM writes, cpu_reset pulsed then released), and the regression where
  * a program whose final 4 bytes equal the boot magic must not re-trigger boot after
  * a load (magicLow is cleared on `sDone`).
  */
class BootFsmTest extends AnyFlatSpec with ChiselScalatestTester {

  private val MAGIC = 0xB007B007
  private val RAM_DEPTH = 8

  private def writeHex(path: Path, words: Seq[Int]): Unit = {
    val text = words.map(w => f"$w%08x").mkString("\n") + "\n"
    Files.writeString(path, text)
  }

  private def withDut(f: BootFsm => Unit): Unit = {
    val hex = Files.createTempFile("bootfsm", ".hex")
    writeHex(hex, Seq(0x00000013, 0x00000013, 0x00000013))
    test(new BootFsm(RAM_DEPTH, hex.toString)) { dut => f(dut) }
  }

  /** One rx byte: poke (sampled on the next posedge), then one idle cycle. */
  private def feedByte(dut: BootFsm, b: Int): Unit = {
    dut.io.rx_valid.poke(true.B)
    dut.io.rx_data.poke(b.U)
    dut.clock.step()
    dut.io.rx_valid.poke(false.B)
    dut.clock.step()
  }

  /** One little-endian word, low byte first. */
  private def feedWord(dut: BootFsm, w: Int): Unit = {
    for (i <- 0 until 4) feedByte(dut, (w >> (8 * i)) & 0xff)
  }

  /** Run the power-on mrom copy + pad, then confirm the FSM is idle and reset is off. */
  private def bootToIdle(dut: BootFsm): Unit = {
    dut.io.boot_en.poke(true.B)
    for (_ <- 0 until RAM_DEPTH * 2) dut.clock.step()
    assert(!dut.io.cpu_reset.peek().litToBoolean, "cpu must leave reset after boot")
  }

  "BootFsm" should "write the mrom image plus padding during boot" in {
    withDut { dut =>
      dut.io.boot_en.poke(true.B)
      var writes = 0
      for (_ <- 0 until 64) {
        if (dut.io.boot.valid.peek().litToBoolean) writes += 1
        dut.clock.step()
      }
      assert(writes == RAM_DEPTH, s"boot should write exactly $RAM_DEPTH words, saw $writes")
      assert(!dut.io.cpu_reset.peek().litToBoolean, "cpu must leave reset after boot")
    }
  }

  it should "load a program on the magic sequence" in {
    withDut { dut =>
      bootToIdle(dut)
      feedWord(dut, MAGIC)
      assert(dut.io.cpu_reset.peek().litToBoolean, "reset must be held after magic")
      feedWord(dut, 0) // addr
      feedWord(dut, 1) // size = 1 word

      // Data word 0xDEADBEEF: feed the first 3 bytes, then the 4th byte makes the
      // write fire combinationally in the same cycle.
      feedByte(dut, 0xef)
      feedByte(dut, 0xbe)
      feedByte(dut, 0xad)
      dut.io.rx_valid.poke(true.B)
      dut.io.rx_data.poke(0xde.U)
      assert(dut.io.boot.valid.peek().litToBoolean, "data word must be written")
      assert(
        dut.io.boot.bits.wdata.peek().litValue == BigInt(0xDEADBEEFL),
        "wdata must equal the loaded word"
      )
      dut.clock.step()
      dut.io.rx_valid.poke(false.B)
      dut.clock.step(2)
      assert(!dut.io.cpu_reset.peek().litToBoolean, "cpu must run again after load")
    }
  }

  it should "treat size=0 as a reset-only command with no RAM writes" in {
    withDut { dut =>
      bootToIdle(dut)
      feedWord(dut, MAGIC)
      assert(dut.io.cpu_reset.peek().litToBoolean, "reset must be held after magic")
      assert(!dut.io.boot.valid.peek().litToBoolean, "no RAM write in sAddr")
      feedWord(dut, 0) // addr
      assert(dut.io.cpu_reset.peek().litToBoolean, "reset must stay held in sSize")
      assert(!dut.io.boot.valid.peek().litToBoolean, "no RAM write in sSize")
      feedWord(dut, 0) // size = 0 → skip sData, straight to sDone
      assert(!dut.io.cpu_reset.peek().litToBoolean, "cpu_reset must be released after size=0")
      assert(!dut.io.boot.valid.peek().litToBoolean, "reset command must not write RAM")

      // Stay idle: no spurious re-boot.
      for (_ <- 0 until 16) {
        assert(!dut.io.cpu_reset.peek().litToBoolean, "must stay idle after reset command")
        assert(!dut.io.boot.valid.peek().litToBoolean, "must not write RAM after reset command")
        dut.clock.step()
      }
    }
  }

  it should "not re-trigger boot when the loaded program ends with the magic word" in {
    withDut { dut =>
      bootToIdle(dut)
      feedWord(dut, MAGIC)
      feedWord(dut, 0) // addr
      feedWord(dut, 1) // size = 1
      feedWord(dut, MAGIC) // the program's final word equals the magic

      // Load complete (sDone cleared magicLow): must stay idle, never re-boot.
      for (_ <- 0 until 16) {
        assert(!dut.io.cpu_reset.peek().litToBoolean, "must not re-enter boot after load")
        assert(!dut.io.boot.valid.peek().litToBoolean, "must not write RAM after load")
        dut.clock.step()
      }
    }
  }
}