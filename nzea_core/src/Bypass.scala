package nzea_core

import mainargs.TokensReader
import upickle.default.{macroRW, readwriter, ReadWriter}

/** A producer that can supply a register read before its value reaches the register file.
  *
  * Only stages a consumer can take a value from exist here: a stage arrives with the hardware that produces it, so a
  * configuration cannot name a link the design does not have. `link` is what reports print — where the value comes from
  * and where it goes.
  *
  * The read happens at the instruction's own issue, so what it can reach is exactly what the file's write port holds
  * that cycle: the instruction retiring there. A producer further up the pipeline is not a stage the read can name —
  * its result is still in flight, and the read waits for it to land instead. Adding a later read (a renaming stage, a
  * scheduler) is what would make another stage reachable.
  */
enum ForwardStage(val link: String):
  /** The commit port: the instruction retiring this cycle writes the register file in the cycle the read happens. */
  case Writeback extends ForwardStage("commit -> register read")

object ForwardStage:

  given ReadWriter[ForwardStage] =
    readwriter[String].bimap(
      _.toString.toLowerCase,
      name =>
        ForwardStage.values
          .find(_.toString.equalsIgnoreCase(name))
          .getOrElse(throw new IllegalArgumentException(s"unknown forward stage '$name'"))
    )

/** Which producers may supply a register read early: the declaration the reader asks.
  *
  * A read names a register the file has not been written with yet whenever the instruction producing it is still in
  * flight — with a link on this list the read takes the producer's value, without one it waits for the write to land,
  * and [[ForwardStage]] says which producer. The empty set is the strictest configuration and costs the reader the
  * cycle the link would have covered.
  *
  * This is a declaration, not hardware: consumers ask [[writeback]] (or a later stage's predicate) and build their own
  * logic from the answer, so a configuration that removes a link removes the logic with it.
  */
case class BypassConfig(stages: Set[ForwardStage]):

  /** The write the commit makes this cycle is visible to the same cycle's read. */
  def writeback: Boolean = stages(ForwardStage.Writeback)

  /** The flag's spelling: a comma-separated list of stages, `none` for the empty set. */
  def name: String =
    if stages.isEmpty then "none" else ForwardStage.values.filter(stages).map(_.toString.toLowerCase).mkString(",")

  /** One line for elaboration output: the links that exist, `none` when nothing forwards. */
  def report: String =
    val names = ForwardStage.values.filter(stages).map(_.link)
    if names.isEmpty then "none" else names.mkString(", ")

object BypassConfig:
  given ReadWriter[BypassConfig] = macroRW

  /** No link: a read sees only what the register file has already written. */
  val none: BypassConfig = BypassConfig(Set.empty)

  /** The commit port's write is visible to the same cycle's read. */
  val writeback: BypassConfig = BypassConfig(Set(ForwardStage.Writeback))

  def parse(token: String): Either[String, BypassConfig] =
    val names = token.split(",").map(_.trim).filter(_.nonEmpty).filterNot(_ == "none")
    val unknown = names.filterNot(n => ForwardStage.values.exists(_.toString.equalsIgnoreCase(n)))
    if unknown.nonEmpty then
      Left(
        s"unknown bypass stage(s): ${unknown.mkString(", ")}; known: none, ${ForwardStage.values.map(_.toString.toLowerCase).mkString(", ")}"
      )
    else
      Right(
        BypassConfig(names.flatMap(n => ForwardStage.values.find(_.toString.equalsIgnoreCase(n))).toSet)
      )

  def parseOrThrow(token: String): BypassConfig =
    parse(token).fold(error => throw new IllegalArgumentException(error), identity)

  /** The flag's spelling for the generator framework: the same token [[parse]] takes. */
  given TokensReader.Simple[BypassConfig] with
    def shortName = "none|writeback"

    def read(strs: Seq[String]): Either[String, BypassConfig] =
      strs.lastOption.map(parse).getOrElse(Left("bypass requires a value"))
