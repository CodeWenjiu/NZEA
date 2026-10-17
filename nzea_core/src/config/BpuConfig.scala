package nzea_core.config

import mainargs.TokensReader
import upickle.default.{readwriter, ReadWriter}

/** A predictor's strategy: the structure the front builds for it. The strategy IS its configuration — the sizes ride in
  * the case, so nothing downstream has a default to fall back on.
  */
enum BpuStrategy:
    /** A pc-indexed 2-bit direction table (`phtEntries` slots) beside a target buffer (`btbEntries` slots). The buffer
      * is written by taken resolutions only and steers a fetch only through a tagged entry, so a fetch that is not a
      * taken branch is never redirected.
      */
    case Bimodal(phtEntries: Int, btbEntries: Int)

    /** The strategy's token: the strategy half of [[BpuConfig.name]]. */
    def name: String = this match
        case Bimodal(phtEntries, btbEntries) => s"bimodal:$phtEntries:$btbEntries"

case class RasConfig(depth: Int):
    require(depth > 0, s"ras depth must be > 0, got $depth")

/** The front's predictor: a strategy and the options that stand beside it.
  *
  * The parameter tree holds `Option[BpuConfig]`, so absence is the sequential fetch — no predictor, and with it none of
  * its options; an `Option` in `None` is an option that is not built. An option any strategy can carry (the
  * return-address stack extends whichever target provider is chosen) is stated here once instead of being repeated in
  * every strategy.
  */
case class BpuConfig(strategy: BpuStrategy, ras: Option[RasConfig]):
    /** The config's token: the strategy's plus one `+<option>` per option present; the config file and the flag both
      * use it.
      */
    def name: String = strategy.name + ras.map(r => s"+ras:${r.depth}").getOrElse("")

object BpuConfig:

    given ReadWriter[BpuConfig] =
        readwriter[String].bimap(
            _.name,
            name => parseOrThrow(name)
        )

    def parse(token: String): Either[String, BpuConfig] =
        token.trim.toLowerCase.split("\\+").toList match
            case strategy :: options =>
                for
                    s <- parseStrategy(strategy)
                    ras <- parseOptions(options)
                yield BpuConfig(s, ras)
            case Nil => Left(s"empty bpu token")

    private def parseStrategy(text: String): Either[String, BpuStrategy] =
        text.split(":") match
            case Array("bimodal", pht, btb) =>
                for
                    p <- pht.toIntOption.toRight(s"pht entries must be a number, got '$pht'")
                    b <- btb.toIntOption.toRight(s"btb entries must be a number, got '$btb'")
                    strategy <- validate(BpuStrategy.Bimodal(p, b))
                yield strategy
            case _ => Left(s"unknown bpu strategy '$text'; known: bimodal:<pht>:<btb>")

    private def powerOfTwo(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0

    private def validate(strategy: BpuStrategy): Either[String, BpuStrategy] = strategy match
        case BpuStrategy.Bimodal(pht, btb) if !powerOfTwo(pht) =>
            Left(s"pht entries must be a power of two, got $pht")
        case BpuStrategy.Bimodal(_, btb) if !powerOfTwo(btb) =>
            Left(s"btb entries must be a power of two, got $btb")
        case _ => Right(strategy)

    private def parseOptions(tokens: List[String]): Either[String, Option[RasConfig]] =
        tokens match
            case Nil => Right(None)
            case token :: Nil =>
                token.split(":") match
                    case Array("ras", depth) =>
                        depth.toIntOption
                            .filter(_ > 0)
                            .toRight(s"ras depth must be a positive number, got '$depth'")
                            .map(d => Some(RasConfig(d)))
                    case _ => Left(s"unknown bpu option '$token'; known: ras:<depth>")
            case _ => Left(s"unknown bpu options '${tokens.mkString("+")}'; known: ras:<depth>")

    def parseOrThrow(token: String): BpuConfig =
        parse(token).fold(error => throw new IllegalArgumentException(error), identity)

    /** The generator framework's reader: the flag takes the same token [[parse]] takes, and an absent flag (the field
      * is an `Option`) is the sequential fetch.
      */
    given TokensReader.Simple[BpuConfig] with
        def shortName = "bimodal:<pht>:<btb>[+ras:<depth>]"

        def read(strs: Seq[String]): Either[String, BpuConfig] =
            strs.lastOption.map(parse).getOrElse(Left("bpu requires a value"))
