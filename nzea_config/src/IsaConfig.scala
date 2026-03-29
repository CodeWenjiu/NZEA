package nzea_config

/** Parsed ISA configuration for Chisel.
  *
  * Base string (before first `_`): `riscv32im`, `rv32im`, `rv64gc`, etc. — single-letter extensions after XLEN.
  *
  * After `_`, named extensions / profiles (lowercase), e.g. `riscv32im_zve32x_zvl128b` for embedded vector
  * (not the full single-letter `V` RVV extension). Those tokens are stored in a [[Set]] — order after the first
  * `_` does not matter (e.g. `..._zve32x_wjcus0` and `..._wjcus0_zve32x` are equivalent).
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

  /** Parse full ISA string, including `_`-separated named extensions. */
  def parse(isa: String): IsaConfig = {
    val trimmed = isa.trim
    if (trimmed.isEmpty) {
      return IsaConfig(xlen = 32, extensions = Set('i'), namedExtensions = Set.empty, zvlBits = None)
    }
    val parts = trimmed.split('_').map(_.trim.toLowerCase).filter(_.nonEmpty).toSeq
    parts.head match {
      case BaseRegex(xlenStr, extStr) =>
        val xlen     = xlenStr.toInt
        val ext      = Option(extStr).filter(_.nonEmpty).getOrElse("i").toSet
        val extSet   = if (ext.isEmpty) Set('i') else ext
        val tailSeq  = parts.tail
        val zvl      = tailSeq.collectFirst { case ZvlRegex(bits) => bits.toInt }
        IsaConfig(xlen, extSet, tailSeq.toSet, zvl)
      case _ =>
        IsaConfig(xlen = 32, extensions = Set('i'), namedExtensions = Set.empty, zvlBits = None)
    }
  }
}
