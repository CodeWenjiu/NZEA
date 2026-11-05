package nzea_rtl.bus

import mainargs.TokensReader

/** A delay stated as a duration: `cycles` of the clock it belongs to, `hz`. It is resolved against the bus clock where
  * it is applied, so a faster bus does not shorten the hardware's own time — an access keeps its nanoseconds.
  *
  * This is the only timing a port can state. How the port applies it (a shift of the answer, a hold of the request) is
  * the device's own logic, not a property of the number.
  */
case class Delay(hz: Int, cycles: Int):
    require(hz >= 1, s"hz must be >= 1, got $hz")
    require(cycles >= 1, s"cycles must be >= 1, got $cycles")

    /** The delay in bus cycles at `busHz`, rounded up once: an access ends inside a cycle, never between two. */
    def at(busHz: Int): Int = ((cycles.toLong * busHz + hz - 1) / hz).toInt

object Delay:

    given upickle.default.ReadWriter[Delay] = upickle.default.macroRW

    given TokensReader.Simple[Delay] with
        def shortName = "delay-json"

        def read(strs: Seq[String]): Either[String, Delay] =
            strs.lastOption.toRight("a delay requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[Delay](text)).toEither.left.map(_.getMessage)
            }
