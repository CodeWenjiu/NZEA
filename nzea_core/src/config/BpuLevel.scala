package nzea_core.config

import mainargs.TokensReader
import upickle.default.{readwriter, ReadWriter}

/** How the front predicts: `Off` is the sequential fetch — its prediction is the fall-through — and every other level
  * names the structure it builds. The level IS its configuration: the sizes ride in the case, so nothing downstream has
  * a default to fall back on and a level that names no table builds none.
  *
  * The levels are points on one structure family: a pc-indexed base table (the bimodal case) is the degenerate form of
  * the tagged geometric predictor the later levels grow into, so a predictor that lands adds tables rather than a
  * second implementation.
  */
enum BpuLevel(val report: String):
    /** No predictor: the fetch predicts the fall-through, and no table exists. */
    case Off extends BpuLevel("off")

    /** A pc-indexed 2-bit direction table (`phtEntries` slots) beside a target buffer (`btbEntries` slots). The buffer
      * is written by taken resolutions only and steers a fetch only through a tagged entry, so a fetch that is not a
      * taken branch is never redirected.
      */
    case Bimodal(phtEntries: Int, btbEntries: Int) extends BpuLevel(s"bimodal pht=$phtEntries btb=$btbEntries")

    /** Whether the fetch carries a prediction at all: a predicted level builds its tables and the pipeline carries the
      * answer; `Off` carries the fall-through and builds nothing.
      */
    def predicted: Boolean = this != BpuLevel.Off

    /** The level's spelling: one token for the config file and the generator's flag. */
    def name: String = this match
        case Off                             => "off"
        case Bimodal(phtEntries, btbEntries) => s"bimodal:$phtEntries:$btbEntries"

object BpuLevel:

    given ReadWriter[BpuLevel] =
        readwriter[String].bimap(
            _.name,
            name => parseOrThrow(name)
        )

    def parse(token: String): Either[String, BpuLevel] =
        token.trim.toLowerCase.split(":") match
            case Array("off") => Right(Off)
            case Array("bimodal", pht, btb) =>
                for
                    p <- pht.toIntOption.toRight(s"pht entries must be a number, got '$pht'")
                    b <- btb.toIntOption.toRight(s"btb entries must be a number, got '$btb'")
                    level <- validate(Bimodal(p, b))
                yield level
            case _ => Left(s"unknown bpu level '$token'; known: off, bimodal:<pht>:<btb>")

    private def powerOfTwo(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0

    private def validate(level: BpuLevel): Either[String, BpuLevel] = level match
        case Bimodal(pht, btb) if !powerOfTwo(pht) => Left(s"pht entries must be a power of two, got $pht")
        case Bimodal(_, btb) if !powerOfTwo(btb)   => Left(s"btb entries must be a power of two, got $btb")
        case _                                     => Right(level)

    def parseOrThrow(token: String): BpuLevel =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    /** The generator framework's reader: the flag takes the same token [[parse]] takes. */
    given TokensReader.Simple[BpuLevel] with
        def shortName = "off|bimodal:<pht>:<btb>"

        def read(strs: Seq[String]): Either[String, BpuLevel] =
            strs.lastOption.map(parse).getOrElse(Left("bpu requires a value"))
