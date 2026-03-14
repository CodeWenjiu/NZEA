package nzea_config

/** Parsed ISA configuration for Chisel to match instruction set extensions.
  *
  * Parses RISC-V ISA strings like "riscv32im", "rv32im", "rv64imafdc".
  * Extensions are single letters: i (base), m, a, f, d, c, etc.
  */
case class IsaConfig(
  xlen: Int,
  extensions: Set[Char]
) {
  /** Check if a single-letter extension is enabled. Case-insensitive. */
  def has(ext: Char): Boolean = extensions(ext.toLower)

  /** M extension: integer multiply/divide */
  def hasM: Boolean = has('m')

  /** A extension: atomic instructions */
  def hasA: Boolean = has('a')

  /** F extension: single-precision floating-point */
  def hasF: Boolean = has('f')

  /** D extension: double-precision floating-point */
  def hasD: Boolean = has('d')

  /** C extension: compressed instructions */
  def hasC: Boolean = has('c')
}

object IsaConfig {
  private val IsaRegex = """(?i)(?:riscv)?(?:rv)?(32|64)([a-z]*)?""".r

  /** Parse ISA string (e.g. "riscv32im", "rv32im") into IsaConfig. */
  def parse(isa: String): IsaConfig = {
    isa match {
      case IsaRegex(xlenStr, extStr) =>
        val xlen = xlenStr.toInt
        val ext = Option(extStr).getOrElse("i").toLowerCase.toSet
        IsaConfig(xlen, if (ext.isEmpty) Set('i') else ext)
      case _ =>
        IsaConfig(xlen = 32, extensions = Set('i'))
    }
  }
}
