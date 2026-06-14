package nzea_rtl

import chisel3._
import chisel3.util.log2Ceil
import scala.io.Source

/** Combinational mask ROM backed by VecInit.
  *
  * Parses a Verilog hex file at elaboration time and emits it as a Mux tree.
  * Zero read latency — data appears the same cycle addr changes.
  *
  * Both depth and dataWidth are auto-detected:
  * - depth   = hex file line count
  * - width   = max hex digits per line × 4, rounded up to next power of 2 (min 8)
  *
  * @param hexPath path to hex file (one word per line), relative to working dir
  */
class Mrom(hexPath: String) extends Module {
  val parsed = Mrom.loadHex(hexPath)
  val words: Seq[BigInt] = parsed.words
  val depth: Int = words.length
  val dataWidth: Int = parsed.dataWidth

  val io = IO(new Bundle {
    val addr = Input(UInt(log2Ceil(depth).W))
    val data = Output(UInt(dataWidth.W))
  })

  io.data := VecInit(words.map(_.U(dataWidth.W)))(io.addr)
}

object Mrom {
  case class Parsed(words: Seq[BigInt], dataWidth: Int)

  def loadHex(path: String): Parsed = {
    val src = Source.fromFile(path)
    try {
      val lines = src.getLines()
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("//") && !l.startsWith("#"))
        .toSeq

      val maxHexLen = if (lines.isEmpty) 0 else lines.map(_.length).max
      val rawBits = maxHexLen * 4
      // Round to next power of 2, floor at 8
      var dw = 8
      while (dw < rawBits) dw <<= 1

      Parsed(
        words = lines.map(l => BigInt(l, 16)),
        dataWidth = dw
      )
    } finally {
      src.close()
    }
  }
}
