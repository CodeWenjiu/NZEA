package nzea_config.core

/** Parsed ISA configuration for Chisel.
  *
  * Base string (before any `_`): `riscv32im`, `rv32im`, `rv64gc`, etc. — single-letter extensions after XLEN. Named
  * (underscore-separated) extension tokens are not supported yet and are rejected loudly; they return together with
  * the features that need them.
  *
  * Parsing rejects unknown base letters so arbitrary strings (e.g. `riscv32sdksa`) do not silently produce a valid
  * config.
  */
case class IsaConfig(
    xlen: Int,
    extensions: Set[Char]
) {
  def has(ext: Char): Boolean = extensions(ext.toLower)

  def hasM: Boolean = has('m')
}

object IsaConfig {
  private val BaseRegex = """(?i)(?:riscv)?(?:rv)?(32|64)([a-z]*)""".r

  /** Single-letter unprivileged extensions allowed in the base rv string (before `_`). Expand when Nzea gains support.
    */
  private val AllowedBaseExtensionLetters: Set[Char] = Set(
    'i', 'e', 'm', 'a', 'f', 'd', 'q', 'c', 'b', 'h', 'j', 'l', 'n', 'p', 't', 'v', 'g'
  )

  /** If `g` is present, expand to IMAFD per RISC-V G = IMAFD + Zicsr + Zifencei (only letter subset tracked here). */
  private def expandBaseExtensions(raw: Set[Char]): Set[Char] =
    if (raw.contains('g')) (raw - 'g') ++ Set('i', 'm', 'a', 'f', 'd') else raw

  /** Parse the ISA string; `_`-separated named extensions are rejected (not supported yet). */
  def parse(isa: String): Either[String, IsaConfig] = {
    val trimmed = isa.trim
    if (trimmed.isEmpty) {
      return Right(IsaConfig(xlen = 32, extensions = Set('i')))
    }
    if (trimmed.contains('_')) {
      return Left(s"named extensions are not supported (got '$trimmed')")
    }
    trimmed.toLowerCase match {
      case BaseRegex(xlenStr, extStr) =>
        val xlen = xlenStr.toInt
        val rawExt = Option(extStr).filter(_.nonEmpty).map(_.toSet).getOrElse(Set('i'))
        val rawSet = if (rawExt.isEmpty) Set('i') else rawExt

        val unknownLetters = rawSet -- AllowedBaseExtensionLetters
        if (unknownLetters.nonEmpty)
          Left(s"unknown base extension letter(s): ${unknownLetters.toSeq.sorted.mkString(", ")}")
        else {
          val extSet = expandBaseExtensions(rawSet)
          if (!extSet.contains('i') && !extSet.contains('e'))
            Left("base ISA must include i or e (or g, which implies i)")
          else Right(IsaConfig(xlen, extSet))
        }
      case other =>
        Left(s"unrecognized ISA base (expected riscv32/rv32 + optional letters): $other")
    }
  }

  /** Parse or throw [[IllegalArgumentException]] (e.g. CLI / config wiring). */
  def parseOrThrow(isa: String): IsaConfig =
    parse(isa) match {
      case Right(c)  => c
      case Left(msg) => throw new IllegalArgumentException(s"Invalid ISA string: $msg")
    }

}