package nzea_rtl.bus

import mainargs.TokensReader

/** What one bus access costs, declared the way the cost arises — and the declaration is the model's statement about
  * which clock the delay belongs to.
  *
  * `Periods(deviceHz, deviceCycles)`: the access occupies `deviceCycles` of the device's own clock — one is a register
  * that answers in its own cycle, twenty are an I2C byte frame's bit periods. The bus sees `ceil(deviceCycles × busHz ÷
  * deviceHz)` cycles, so the access keeps its duration when the bus clock changes. This is the form for a device in its
  * own clock domain.
  *
  * `SameDomain(busCycles)`: the device is clocked by the bus itself, so the access is that many bus cycles at any
  * platform clock — a faster clock shortens it in time and the count stays. What fills those cycles does not matter to
  * the model: pipeline stages, or a state machine's count like the UART's three. This is the form for every device with
  * no clock of its own, which is what makes the hz = bus hz assumption explicit instead of a default.
  *
  * `Sum(parts)`: the access is several delays by origin — a memory's own column latency plus the controller in front of
  * it. Each part keeps its own declaration, so a sourced part and an unsourced one stay tellable apart, and the total
  * is rounded once, not per part.
  */
enum AccessCost:
    case Periods(deviceHz: Int, deviceCycles: Int)
    case SameDomain(busCycles: Int)
    case Sum(parts: Seq[AccessCost])

    /** The access in bus cycles at `busHz`, rounded up once: an access ends inside a cycle, never between two. The sum
      * is exact until the end — rounding each part would add up to a cycle per part.
      */
    def at(busHz: Int): Int =
        val (numerator, denominator) = exact(busHz)
        ((numerator + denominator - 1) / denominator).toInt

    /** The access as the rational `numerator / denominator` bus cycles, so parts can be added before rounding. */
    private def exact(busHz: Int): (BigInt, BigInt) = this match
        case Periods(deviceHz, deviceCycles) => (BigInt(deviceCycles) * busHz, BigInt(deviceHz))
        case SameDomain(busCycles)           => (BigInt(busCycles), BigInt(1))
        case Sum(parts) =>
            parts.foldLeft((BigInt(0), BigInt(1))) { case ((n, d), part) =>
                val (partN, partD) = part.exact(busHz)
                (n * partD + partN * d, d * partD)
            }

    private[nzea_rtl] def requireValid(): Unit = this match
        case Periods(deviceHz, deviceCycles) =>
            require(deviceHz >= 1, s"deviceHz must be >= 1, got $deviceHz")
            require(deviceCycles >= 1, s"deviceCycles must be >= 1, got $deviceCycles")
        case SameDomain(busCycles) => require(busCycles >= 1, s"busCycles must be >= 1, got $busCycles")
        case Sum(parts) =>
            require(parts.nonEmpty, "a cost sum needs at least one part")
            parts.foreach(_.requireValid())

object AccessCost:

    /** A bit-serial frame: `bits` bit periods of a wire clocked at `bitHz`. The wire's own reading of `Periods` — the
      * clock is a bit clock, so a period is a bit — kept as a name so an entry says which quantity it counted.
      */
    def bitFrame(bitHz: Int, bits: Int): AccessCost = Periods(bitHz, bits)

    private def render(cost: AccessCost): String = cost match
        case AccessCost.Periods(deviceHz, deviceCycles) => s"Periods:$deviceHz:$deviceCycles"
        case AccessCost.SameDomain(busCycles)           => s"SameDomain:$busCycles"
        case AccessCost.Sum(parts)                      => s"Sum:${parts.map(render).mkString("|")}"

    private def parse(text: String): Either[String, AccessCost] = text match
        case s if s.startsWith("Sum:") =>
            s.stripPrefix("Sum:")
                .split("\\|")
                .toSeq
                .foldLeft[Either[String, Seq[AccessCost]]](Right(Seq.empty)) { (acc, part) =>
                    acc.flatMap(done => parse(part).map(done :+ _))
                }
                .map(AccessCost.Sum.apply)
        case s if s.startsWith("Periods:") =>
            s.stripPrefix("Periods:").split(":") match
                case Array(hz, cycles) =>
                    scala.util.Try(AccessCost.Periods(hz.toInt, cycles.toInt)).toEither.left.map(_.getMessage)
                case _ => Left(s"malformed access cost '$s' (expected Periods:<deviceHz>:<cycles>)")
        case s if s.startsWith("SameDomain:") =>
            scala.util.Try(AccessCost.SameDomain(s.stripPrefix("SameDomain:").toInt)).toEither.left.map(_.getMessage)
        case other =>
            Left(
                s"unknown access cost '$other' (expected SameDomain:<cycles>|Periods:<deviceHz>:<cycles>|Sum:<part>|<part>)"
            )

    /** One spelling for the config file and the flag, the `DeviceKind` shape: the case name and its numbers. */
    given upickle.default.ReadWriter[AccessCost] = upickle.default
        .readwriter[String]
        .bimap(render, text => parse(text).fold(error => throw new IllegalArgumentException(error), identity))

    given TokensReader.Simple[AccessCost] with
        def shortName = "SameDomain:<cycles>|Periods:<deviceHz>:<cycles>|Sum:<part>|<part>"

        def read(strs: Seq[String]): Either[String, AccessCost] =
            strs.lastOption.toRight("an access cost requires a value").flatMap(parse)

/** How long a port stays busy for one access, beside how long its answer takes.
  *
  * `Pipelined`: the port takes the next request as soon as this one's answer is offered, so a long latency does not cap
  * throughput — the shape of a device on a port of its own.
  *
  * `Held`: the port is occupied for the access's whole cost, one access at a time — a wire's frame. A port several
  * devices share is one port, so their `Held` rows also queue against each other.
  */
enum Occupancy:
    case Pipelined
    case Held

object Occupancy:

    given upickle.default.ReadWriter[Occupancy] = upickle.default
        .readwriter[String]
        .bimap(_.toString, text => Occupancy.valueOf(text))

/** One row of a port's timing table: the addresses it answers for, what an access costs, and whether the port is held.
  * `range = None` is the catch-all a table has at most one of.
  */
case class TimingEntry(range: Option[AddrRange], cost: AccessCost, occupancy: Occupancy):
    /** Whether this row answers for `addr`. A row without a range answers for every address. */
    def covers(addr: BigInt): Boolean = range.forall(_.contains(addr))

given upickle.default.ReadWriter[TimingEntry] = upickle.default.macroRW

/** One port's timing, as a table over the addresses it serves.
  *
  * A device on a port of its own is a table with one catch-all row; a window in front of several devices is a table
  * with a row per device. The port is one, so the `Held` rows also serialise against each other — the physical fact a
  * shared wire imposes, which a per-device delay cannot state.
  */
case class BusTiming(entries: Seq[TimingEntry], jitterEvery: Int, jitterSpan: Int):

    require(entries.nonEmpty, "a port timing needs at least one entry")
    require(entries.count(_.range.isEmpty) == 1, "exactly one catch-all row: a port answers every address")

    require(
        entries.filter(_.range.isEmpty).forall(_.occupancy == Occupancy.Pipelined),
        "the catch-all row is the port's line; a held port would need a wire for every address, which this model does not state"
    )

    require(
        entries.count(_.occupancy == Occupancy.Pipelined) == 1,
        "one line cost: two pipelined rows would need per-address latency, which the line does not have"
    )

    require(jitterEvery >= 1 && jitterEvery <= 256, s"jitterEvery must be in [1, 256], got $jitterEvery")
    require(jitterSpan >= 1, s"jitterSpan must be >= 1, got $jitterSpan")
    entries.foreach(entry => entry.cost.requireValid())

    private val ranges: Seq[AddrRange] = entries.flatMap(_.range)

    require(
        ranges.indices.forall(i => ranges.indices.forall(j => i == j || !ranges(i).overlaps(ranges(j)))),
        "two rows explain the same address"
    )

    /** The port's line: every address no other row claims, at the port's own pace. The catch-all row and the one
      * pipelined row are the same row, which a table with a range on the line would otherwise be able to split.
      */
    def line: TimingEntry = entries.find(_.range.isEmpty).get

    /** The rows that hold the port. */
    def held: Seq[TimingEntry] = entries.filter(_.occupancy == Occupancy.Held)

object BusTiming:

    /** The generator framework needs a reader for every parameter field; this is not a user-facing knob — the
      * platform's tables live in `nzea_device.DeviceTimings`.
      */
    given TokensReader.Simple[BusTiming] with
        def shortName = "timing-json"

        def read(strs: Seq[String]): Either[String, BusTiming] =
            strs.lastOption.toRight("a port timing requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[BusTiming](text)).toEither.left.map(_.getMessage)
            }

    given upickle.default.ReadWriter[BusTiming] = upickle.default.macroRW
