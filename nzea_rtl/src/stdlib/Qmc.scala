package nzea_rtl.stdlib

import scala.collection.mutable

import me.jiuyang.decoder.{BitPat, BitSet, PLA, TruthTable}

/** In-process replacement for `me.jiuyang.decoder.espresso`: same input, same output, no external binary.
  *
  * `espresso` shells out to the Berkeley minimizer. This runs Quine–McCluskey over the *cubes* a decode table already
  * consists of (a table's entries are wide patterns, not minterms), so the cost follows the number of entries rather
  * than 2^variables. The result is a PLA with the same semantics as `espresso`'s, so a consumer of either (zaozi's
  * `PLADecoder`, our [[PlaPlan]]) reads them identically:
  *
  *   - the tables are baked into one PLA, first table in the high bits, one row per distinct input pattern;
  *   - every output bit's onset is covered exactly, and the cover is chosen over all bits at once, so a cube several
  *     bits can use costs one AND term instead of one per bit.
  *
  * A bit whose default asserts is inverted by the hardware's OR plane, so its cover is the set of rows that pull it
  * low; every other bit is covered on the rows that raise it. That per-bit choice is what `espresso`'s split-by-default
  * passes end up doing, done directly on the baked PLA. The cover is kept *exact*: a bit is asserted on its function
  * and nowhere else, which the OR plane requires, since it sums the rows that match and nothing more. It is picked
  * greedily rather than as a proven minimum, so it can be a little larger than `espresso`'s — in exchange the OR plane
  * is narrower, because no bit ends up fed by more rows than its own cover.
  */
object Qmc:

    /** The tables' fields baked into one minimized PLA. */
    def apply(tables: Seq[TruthTable]): PLA =
        require(tables.nonEmpty, "Qmc needs at least one table")
        minimize(bake(tables))

    /** One row per distinct input pattern, the tables' encodings concatenated (first table in the high bits). */
    private def bake(tables: Seq[TruthTable]): PLA =
        PLA(
            tables
                .flatMap(_.table.map(_._1))
                // `BitSet` equality is semantic but hashes by identity, so `distinct` cannot collapse equal patterns.
                .distinctBy(_.toString)
                .map: input =>
                    input -> tables
                        .map(t => t.table.find(_._1 == input).map(_._2).getOrElse(t.encoding(t.default)))
                        .reduce(_ ## _),
            tables.map(t => t.encoding(t.default)).reduce(_ ## _)
        )

    /** One baked PLA: the onsets of its output bits, then a row per cube the cover selects. */
    private def minimize(pla: PLA): PLA =
        val onsets = Seq.tabulate(pla.outputWidth): bit =>
            val inverted = pla.default.mask.testBit(bit) && pla.default.value.testBit(bit)
            pla.table
                .collect:
                    case (in, out) if out.mask.testBit(bit) && (out.value.testBit(bit) != inverted) => in
                .distinctBy(_.toString)

        val rows = cover(onsets)
            .sortBy((cube, _) => cube.toString)
            .map: (cube, bits) =>
                val asserted = bits.foldLeft(BigInt(0))((acc, bit) => acc | (BigInt(1) << bit))
                cube -> BitSet.bitpat(asserted, asserted, pla.outputWidth)

        PLA(rows, pla.default)

    /** An exact cover of every bit's onset: one row per chosen cube, and the bits that cube asserts.
      *
      * The cover is picked over all output bits at once, so a cube several bits can use costs one AND term instead of
      * one per bit. A bit's onset is covered by *cubes* rather than by its minterms: a prime implicant is built by
      * merging onset cubes, so it never reaches outside the onset, and every onset cube is inside a prime (merging only
      * grows cubes). That keeps the cover exact, which the hardware's OR plane requires.
      */
    private def cover(onsets: Seq[Seq[BitPat]]): Seq[(BitPat, Seq[Int])] =
        // A cube is a candidate for every bit whose primes contain it; several bits naming the same cube share one row.
        val candidates = onsets.indices
            .flatMap(bit => primeImplicants(onsets(bit)).map(cube => (cube.toString, cube, bit)))
            .groupBy(_._1)
            .toSeq
            .sortBy(_._1)
            .map: (_, entries) =>
                val cube = entries.head._2
                (cube, entries.map(_._3).distinct.sorted)

        // Per bit, the onset cubes still waiting for a cube that contains them.
        var pending = onsets
        val chosen = mutable.LinkedHashMap.empty[String, (BitPat, mutable.BitSet)]

        def take(cube: BitPat, bits: Seq[Int]): Unit =
            chosen.get(cube.toString) match
                case Some((_, asserted)) => bits.foreach(asserted += _)
                case None                => chosen(cube.toString) = (cube, mutable.BitSet.fromSpecific(bits))
            pending = pending.indices.map: bit =>
                if bits.contains(bit) then pending(bit).filterNot(row => contains(cube, row)) else pending(bit)

        while pending.exists(_.nonEmpty) do
            val scored = candidates.map: (cube, bits) =>
                val covered = bits.filter(bit => pending(bit).exists(row => contains(cube, row)))
                (cube, covered, covered.map(bit => pending(bit).count(row => contains(cube, row))).sum)
            scored
                .filter { case (_, covered, _) => covered.nonEmpty }
                .maxByOption { case (cube, _, hits) => (hits, -cube.mask.bitCount) } match
                case Some((cube, bits, _)) => take(cube, bits)
                case None                  =>
                    // Nothing covers what is left, so the onset cube becomes its own row; the onsets started as a cover.
                    pending.indices.foreach(bit => pending(bit).foreach(row => take(row, Seq(bit))))
                    pending = pending.map(_ => Seq.empty)

        chosen.values.toSeq.map((cube, bits) => (cube, bits.toSeq))

    /** Every implicant of a bit's onset: merge neighbours level by level, keeping the cubes no merge consumed. */
    private def primeImplicants(rows: Seq[BitPat]): Seq[BitPat] =
        val cubes = mutable.LinkedHashMap.empty[String, BitPat]
        val isPrime = mutable.LinkedHashMap.empty[String, Boolean]
        rows.foreach: row =>
            cubes(row.toString) = row
            isPrime(row.toString) = true
        var frontier = rows
        while frontier.nonEmpty do
            val next = mutable.LinkedHashMap.empty[String, BitPat]
            for
                i <- frontier.indices
                j <- (i + 1) until frontier.length
                if adjacent(frontier(i), frontier(j))
            do
                isPrime(frontier(i).toString) = false
                isPrime(frontier(j).toString) = false
                val joined = join(frontier(i), frontier(j))
                if !cubes.contains(joined.toString) then
                    cubes(joined.toString) = joined
                    isPrime(joined.toString) = true
                    next(joined.toString) = joined
            frontier = next.values.toSeq
        isPrime.collect { case (key, true) => cubes(key) }.toSeq

    /** `general` covers `specific`: it specifies no more literals and agrees on the ones it does. */
    private def contains(general: BitPat, specific: BitPat): Boolean =
        (general.mask & ~specific.mask) == 0 && ((general.value ^ specific.value) & general.mask) == 0

    /** Two cubes specify the same literals and disagree on exactly one of them, so dropping that literal joins them. */
    private def adjacent(lhs: BitPat, rhs: BitPat): Boolean =
        (lhs.mask ^ rhs.mask) == 0 && (lhs.value ^ rhs.value).bitCount == 1

    /** The cube covering both: the literals they agree on, the one they differ in dropped. */
    private def join(lhs: BitPat, rhs: BitPat): BitPat =
        val mask = lhs.mask & ~(lhs.value ^ rhs.value)
        BitSet.bitpat(lhs.value & mask, mask, lhs.width)
