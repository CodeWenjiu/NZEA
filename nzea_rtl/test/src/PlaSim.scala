package nzea_rtl

import me.jiuyang.decoder.{PLA, TruthTable}

/** Reference semantics of a decode PLA, for tests: what a consumer of the minimized PLA must recover from it.
  *
  * The OR plane sums the rows that match the input, and an output bit is inverted exactly where the default asserts (a
  * `?` in the default asserts nothing). Both `me.jiuyang.stdlib.default.PLADecoder` and our `PlaPlan.decode` build
  * their logic this way, so a minimized PLA can be checked against the tables it came from without simulating hardware.
  */
object PlaSim:

  /** What the tables say a word decodes to: per table the matching entry's encoding, else the default's, concatenated
    * with the first table in the high bits.
    */
  def reference(tables: Seq[TruthTable], word: BigInt): BigInt =
    tables.foldLeft(BigInt(0)): (acc, table) =>
      val encoded = table.table
        .collectFirst { case (in, out) if (word & in.mask) == in.value => out }
        .getOrElse(table.encoding(table.default))
      (acc << table.outputBits) | encoded.value

  /** What the minimized PLA reports for the same word. */
  def decode(pla: PLA, word: BigInt): BigInt =
    val asserted = pla.table.foldLeft(BigInt(0)): (bits, row) =>
      val (in, out) = row
      if (word & in.mask) == in.value then bits | (out.value & out.mask) else bits
    asserted ^ pla.default.value
