package nzea_config

/** Parsed ISA configuration for Chisel.
  *
  * Base string (before first `_`): `riscv32im`, `rv32im`, `rv64gc`, etc. — single-letter extensions after XLEN.
  *
  * After `_`, named extensions / profiles (lowercase), e.g. `riscv32im_zve32x_zvl128b` for embedded vector
  * (not the full single-letter `V` RVV extension). Those tokens are stored in a [[Set]] — order after the first
  * `_` does not matter (e.g. `..._zve32x_wjcus0` and `..._wjcus0_zve32x` are equivalent).
  *
  * Parsing rejects unknown base letters and unknown `_` tokens so arbitrary strings (e.g. `riscv32sdksa`)
  * do not silently produce a valid config.
  */
case class IsaConfig(
  xlen: Int,
  extensions: Set[Char],
  /** Tokens after first `_`, lowercased, e.g. `zve32x`, `zvl128b`. */
  namedExtensions: Set[String],
  /** First `zvl{N}b` token in underscore order (e.g. `zvl128b` → 128). */
  zvlBits: Option[Int]
) {
  def has(ext: Char): Boolean = extensions(ext.toLower)

  def hasM: Boolean = has('m')
  def hasA: Boolean = has('a')
  def hasF: Boolean = has('f')
  def hasD: Boolean = has('d')
  def hasC: Boolean = has('c')

  /** Zve32x: embedded 32-bit integer vector profile. */
  def hasZve32x: Boolean = namedExtensions.contains("zve32x")

  /** Zve64x: embedded 64-bit integer vector profile. */
  def hasZve64x: Boolean = namedExtensions.contains("zve64x")

  /** True if any Zve* profile token is present. */
  def hasEmbeddedVector: Boolean = hasZve32x || hasZve64x

  /** Wjcus0: custom extension 0 (placeholder; no functional enablement yet). */
  def hasWjcus0: Boolean = namedExtensions.contains("wjcus0")
}

object IsaConfig {
  private val BaseRegex = """(?i)(?:riscv)?(?:rv)?(32|64)([a-z]*)""".r
  private val ZvlRegex  = """(?i)^zvl(\d+)b$""".r

  /** Single-letter unprivileged extensions allowed in the base rv string (before `_`). Expand when Nzea gains support. */
  private val AllowedBaseExtensionLetters: Set[Char] = Set(
    'i', 'e',
    'm', 'a', 'f', 'd', 'q', 'c',
    'b', 'h', 'j', 'l', 'n', 'p', 't', 'v',
    'g'
  )

  private val KnownNamedExtensionTokens: Set[String] = Set(
    "zve32x",
    "zve64x",
    "wjcus0"
  )

  private def isKnownNamedToken(token: String): Boolean =
    (token match { case ZvlRegex(_) => true; case _ => false }) || KnownNamedExtensionTokens.contains(token)

  /** If `g` is present, expand to IMAFD per RISC-V G = IMAFD + Zicsr + Zifencei (only letter subset tracked here). */
  private def expandBaseExtensions(raw: Set[Char]): Set[Char] =
    if (raw.contains('g')) (raw - 'g') ++ Set('i', 'm', 'a', 'f', 'd') else raw

  /** Parse full ISA string, including `_`-separated named extensions. */
  def parse(isa: String): Either[String, IsaConfig] = {
    val trimmed = isa.trim
    if (trimmed.isEmpty) {
      return Right(IsaConfig(xlen = 32, extensions = Set('i'), namedExtensions = Set.empty, zvlBits = None))
    }
    val parts = trimmed.split('_').map(_.trim.toLowerCase).filter(_.nonEmpty).toSeq
    parts.head match {
      case BaseRegex(xlenStr, extStr) =>
        val xlen   = xlenStr.toInt
        val rawExt = Option(extStr).filter(_.nonEmpty).map(_.toSet).getOrElse(Set('i'))
        val rawSet = if (rawExt.isEmpty) Set('i') else rawExt

        val unknownLetters = rawSet -- AllowedBaseExtensionLetters
        if (unknownLetters.nonEmpty)
          Left(s"unknown base extension letter(s): ${unknownLetters.toSeq.sorted.mkString(", ")}")
        else {
          val extSet = expandBaseExtensions(rawSet)
          if (!extSet.contains('i') && !extSet.contains('e'))
            Left("base ISA must include i or e (or g, which implies i)")
          else {
            val tailSeq = parts.tail.toSeq
            val unknownNamed = tailSeq.filterNot(isKnownNamedToken)
            if (unknownNamed.nonEmpty)
              Left(s"unknown named extension(s): ${unknownNamed.mkString(", ")}")
            else {
              val namedSet = tailSeq.toSet
              val zvl      = tailSeq.collectFirst { case ZvlRegex(bits) => bits.toInt }
              Right(IsaConfig(xlen, extSet, namedSet, zvl))
            }
          }
        }
      case other =>
        Left(s"unrecognized ISA base (expected riscv32/rv32 + optional letters): $other")
    }
  }

  /** Parse or throw [[IllegalArgumentException]] (e.g. CLI / config wiring). */
  def parseOrThrow(isa: String): IsaConfig =
    parse(isa) match {
      case Right(c) => c
      case Left(msg) => throw new IllegalArgumentException(s"Invalid ISA string: $msg")
    }
}
