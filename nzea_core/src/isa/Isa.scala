package nzea_core.isa

import mainargs.TokensReader
import upickle.default.{macroRW, readwriter, ReadWriter}

/** The register width a configuration is built for.
  *
  * One architectural width, so every derived width (data, pc, address, bus, lane strobe) reads it here instead of being
  * passed as a bare number: `bits` is what sizes a signal, `lanes` is the byte count of one machine word.
  */
enum Xlen(val bits: Int):
    case Rv32 extends Xlen(32)
    case Rv64 extends Xlen(64)

    /** Bytes in one machine word: the bus lane count, and the width of a byte strobe. */
    val lanes: Int = bits / 8

object Xlen:

    given ReadWriter[Xlen] =
        readwriter[Int].bimap(
            _.bits,
            bits => values.find(_.bits == bits).getOrElse(throw new IllegalArgumentException(s"no Xlen of $bits bits"))
        )

/** The unprivileged single-letter extensions a configuration can carry, in the order the ISA string spells them (RISC-V
  * puts I/E first, then M A F D Q L C B and the rest).
  *
  * The set only says which extensions are on; what a module does with one is that module's own question (an op set
  * gating its members, say). `g` is not a member: an ISA string's `g` expands to its members before it gets here.
  */
enum Ext(val letter: Char):
    case I extends Ext('i')
    case E extends Ext('e')
    case M extends Ext('m')
    case A extends Ext('a')
    case F extends Ext('f')
    case D extends Ext('d')
    case Q extends Ext('q')
    case L extends Ext('l')
    case C extends Ext('c')
    case B extends Ext('b')
    case J extends Ext('j')
    case T extends Ext('t')
    case P extends Ext('p')
    case V extends Ext('v')
    case N extends Ext('n')
    case H extends Ext('h')

object Ext:
    def of(letter: Char): Option[Ext] = values.find(_.letter == letter)

    given ReadWriter[Ext] =
        readwriter[String].bimap(
            _.letter.toString,
            s => of(s.head).getOrElse(throw new IllegalArgumentException(s"no extension '$s'"))
        )

/** A configuration's ISA: the register width plus the extensions that are on.
  *
  * Parsing and the canonical string live here, so a holder of an [[Isa]] neither keeps a string nor re-derives a width.
  * The string is the *canonical* one (`riscv32im`): extensions come out in declaration order, so it does not depend on
  * how the set was built, and it is what paths, reports and the simulators use.
  */
case class Isa(xlen: Xlen, extensions: Set[Ext]):
    def has(ext: Ext): Boolean = extensions.contains(ext)

    def hasM: Boolean = has(Ext.M)

    def name: String = s"riscv${xlen.bits}${Ext.values.filter(has).map(_.letter).mkString}"

    override def toString: String = name

object Isa:
    given ReadWriter[Isa] = macroRW

    /** The flag's spelling for the generator framework: the same token [[parse]] takes, so `--isa riscv32im` reaches a
      * module as an [[Isa]] and nothing downstream parses a string.
      */
    given TokensReader.Simple[Isa] with
        def shortName = "riscv<32|64>[imafd...]"

        def read(strs: Seq[String]): Either[String, Isa] =
            strs.lastOption.map(parse).getOrElse(Left("isa requires a value"))

    /** Base string (before any `_`): `riscv32im`, `rv32im`, `rv64gc`, etc. — single-letter extensions after XLEN. Named
      * (underscore-separated) extension tokens are not supported yet and are rejected loudly; they return together with
      * the features that need them.
      */
    private val BaseRegex = """(?i)(?:riscv)?(?:rv)?(32|64)([a-z]*)""".r

    /** Letters a base rv string may carry: the extensions, plus `g`, which is an alias expanded below. */
    private val AllowedLetters: Set[Char] = Ext.values.map(_.letter).toSet + 'g'

    /** If `g` is present, expand to IMAFD per RISC-V G = IMAFD + Zicsr + Zifencei (only letter subset tracked here). */
    private def expandG(raw: Set[Char]): Set[Char] =
        if raw.contains('g') then (raw - 'g') ++ Set('i', 'm', 'a', 'f', 'd') else raw

    /** Parse the ISA string; `_`-separated named extensions are rejected (not supported yet). Unknown base letters are
      * rejected too, so arbitrary strings (e.g. `riscv32sdksa`) do not silently produce a valid config.
      */
    def parse(isa: String): Either[String, Isa] =
        val trimmed = isa.trim
        if trimmed.isEmpty then Right(Isa(Xlen.Rv32, Set(Ext.I)))
        else if trimmed.contains('_') then Left(s"named extensions are not supported (got '$trimmed')")
        else
            trimmed.toLowerCase match
                case BaseRegex(bits, extStr) =>
                    val xlen = Xlen.values.find(_.bits == bits.toInt).get
                    val rawExt = Option(extStr).filter(_.nonEmpty).map(_.toSet).getOrElse(Set('i'))
                    val rawSet = if rawExt.isEmpty then Set('i') else rawExt

                    val unknownLetters = rawSet -- AllowedLetters
                    if unknownLetters.nonEmpty then
                        Left(s"unknown base extension letter(s): ${unknownLetters.toSeq.sorted.mkString(", ")}")
                    else
                        val extSet = expandG(rawSet).map(Ext.of(_).get)
                        if !extSet.contains(Ext.I) && !extSet.contains(Ext.E) then
                            Left("base ISA must include i or e (or g, which implies i)")
                        else Right(Isa(xlen, extSet))
                case other =>
                    Left(s"unrecognized ISA base (expected riscv32/rv32 + optional letters): $other")

    /** Parse or throw [[IllegalArgumentException]] (e.g. CLI / config wiring). */
    def parseOrThrow(isa: String): Isa =
        parse(isa) match
            case Right(c)  => c
            case Left(msg) => throw new IllegalArgumentException(s"Invalid ISA string: $msg")

    /** Parse-or-throw, for the call sites that spell an ISA out (`Isa("riscv32i")`). */
    def apply(isa: String): Isa = parseOrThrow(isa)
