package nzea_core

import mainargs.TokensReader
import upickle.default.{readwriter, ReadWriter}

/** How deep the read's forwarding reaches: the level names the youngest producer the read may take a value from.
  *
  * Producers come back in flight in one order — the completing instruction, then the committing one, then the file — so
  * a level is a prefix of that ladder: the shallower links come with it by construction, and the ladder's end is as far
  * as a read can reach. [[Commit]] is where the file reads as write-first; [[Execute]] takes the completing
  * instruction's result through the completion channel, before it reaches the commit port.
  *
  * What each level buys, in cycles a payload is held for a read that names an in-flight write: at [[Execute]] none, at
  * [[Commit]] one while the producer is in execute and none while it commits; one level down, one more in either case.
  *
  * This is a declaration, not hardware: consumers ask a predicate and build their own logic from the answer, so a level
  * that removes a link removes the logic with it.
  */
enum BypassLevel(val report: String):
  /** The file only: a read sees what the file has already been written with. */
  case None extends BypassLevel("none")

  /** The commit port: the write the committing instruction makes this cycle is visible to the same cycle's read. */
  case Commit extends BypassLevel("commit -> register read")

  /** The completion channel: the completing instruction's result is visible to the same cycle's read, before it reaches
    * the commit port.
    */
  case Execute extends BypassLevel("execute -> register read")

  /** The write the commit makes this cycle is visible to the same cycle's read. */
  def commit: Boolean = ordinal >= BypassLevel.Commit.ordinal

  /** The completing instruction's result is visible to the same cycle's read. */
  def execute: Boolean = ordinal >= BypassLevel.Execute.ordinal

  /** The level's spelling: one token for the config file and the generator's flag. */
  def name: String = toString.toLowerCase

object BypassLevel:

  given ReadWriter[BypassLevel] =
    readwriter[String].bimap(
      _.name,
      name => parseOrThrow(name)
    )

  def parse(token: String): Either[String, BypassLevel] =
    values
      .find(_.name.equalsIgnoreCase(token.trim))
      .toRight(s"unknown bypass level '$token'; known: ${values.map(_.name).mkString(", ")}")

  def parseOrThrow(token: String): BypassLevel =
    parse(token).fold(error => throw new IllegalArgumentException(error), identity)

  /** The generator framework's reader: the flag takes the same token [[parse]] takes. */
  given TokensReader.Simple[BypassLevel] with
    def shortName = values.map(_.name).mkString("|")

    def read(strs: Seq[String]): Either[String, BypassLevel] =
      strs.lastOption.map(parse).getOrElse(Left("bypass requires a value"))
