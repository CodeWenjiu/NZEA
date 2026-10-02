package nzea_top

import mainargs.{arg, ParserForClass, TokensReader}
import nzea_core.isa.Isa

/** `CoreConfig` as flags, with the baseline as the fields' defaults.
  *
  * A mirror, not the config itself: mainargs resolves a nested group's defaults from the group's own fields, and the
  * design types carry none (rule 6), so the parse group that lets one flag be overridden alone is its own case class —
  * this one, beside the baseline it defaults from. `CliArgs.parameter` assembles `TopConfig` out of it, so a field the
  * tree gains breaks that construction at compile time.
  */
case class CoreConfigArgs(
    @arg(doc = "instruction set") isa: Isa = CoreConfigArgs.baseline.isa,
    @arg(doc = "reset pc, hex (0x…) or decimal") defaultPc: Long = CoreConfigArgs.baseline.defaultPc
)

object CoreConfigArgs:

    private val baseline = NzeaCoreParameter.default.config.core

    /** mainargs' builtin `Long` reader takes decimal only (`toLong`); a pc takes hex too, so this scope-local reader
      * shadows it with the old flag's decode (`Long.decode`: `0x80000000` and `80000000` both work).
      */
    given TokensReader.Simple[Long] with
        def shortName = "long"

        def read(strs: Seq[String]): Either[String, Long] =
            strs.lastOption match
                case None => Left("a pc requires a value")
                case Some(str) =>
                    try Right(java.lang.Long.decode(str))
                    catch case _: NumberFormatException => Left(s"invalid pc '$str' (hex or decimal)")

    given TokensReader[CoreConfigArgs] = ParserForClass[CoreConfigArgs]
