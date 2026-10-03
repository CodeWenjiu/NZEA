package nzea_rtl.bus

import mainargs.TokensReader

/** A slave's window in the address map: `[base, base + size)`. */
case class AddrRange(base: BigInt, size: BigInt):
    require(base >= 0, s"base must be >= 0, got $base")
    require(size > 0, s"size must be > 0, got $size")

    val endExclusive: BigInt = base + size

    def contains(addr: BigInt): Boolean = addr >= base && addr < endExclusive

    /** Whether the two windows share an address — the condition a decode cannot have. */
    def overlaps(other: AddrRange): Boolean = base < other.endExclusive && other.base < endExclusive

/** What a slave is, from the address map's point of view.
  *
  * `Mmio` is a register window with side effects — the distinction the commit trace reports as `is_mmio`, and the one
  * that decides which addresses are plain memory. It is a property of the map rather than an address comparison, so a
  * consumer never hard-codes "everything but RAM is MMIO".
  */
enum SlaveKind:
    case Memory
    case Mmio

/** One master port into the fabric: its name and the payload widths it speaks. */
case class MasterPort(name: String, userWidth: Int, idWidth: Int)

/** One slave on the fabric: its name, its window, what it is, and how many requests it may hold at once. */
case class SlavePort(name: String, range: AddrRange, kind: SlaveKind, outstanding: Int = 1)

object SlavePort:

    given upickle.default.ReadWriter[SlavePort] = upickle.default.macroRW

    /** The flag's spelling is one device's own JSON form — the map's serialization, so a device list round-trips. A
      * `Seq[SlavePort]` flag is this repeated (`--devices <json> --devices <json>`).
      */
    given TokensReader.Simple[SlavePort] with
        def shortName = "device-json"

        def read(strs: Seq[String]): Either[String, SlavePort] =
            strs.lastOption.toRight("a device requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[SlavePort](text)).toEither.left.map(_.getMessage)
            }

/** A level's bus address map and port inventory.
  *
  * The single description the whole fabric derives from — the crossbar's port count, its address decode and the
  * caller's port lookup all read this, and so do the consumers that are not the RTL: the commit trace's `is_mmio`
  * ranges, the platform's simulation device models and its board port list. Adding a slave is one entry here instead of
  * an edit in every one of those lists. Names are the handle: a caller asks for `slave("ram")` and never counts an
  * index.
  *
  * `userWidth` / `idWidth` are the fabric's: every master port carries the widest payload, and a master that speaks a
  * narrower one is adapted by its owner. `outstanding` is per slave.
  */
case class AddressMap(masters: Seq[MasterPort], slaves: Seq[SlavePort]):
    require(masters.nonEmpty, "an address map needs at least one master")
    require(slaves.nonEmpty, "an address map needs at least one slave")
    require(masters.map(_.name).distinct.sizeIs == masters.size, "master names must be unique")
    require(slaves.map(_.name).distinct.sizeIs == slaves.size, "slave names must be unique")

    // Overlapping windows would make the decode ambiguous; the address map is its owner's to keep consistent.
    for i <- slaves.indices; j <- i + 1 until slaves.size do
        require(
            !slaves(i).range.overlaps(slaves(j).range),
            s"slave windows overlap: '${slaves(i).name}' and '${slaves(j).name}'"
        )

    /** The fabric's master payload width: the widest a master speaks. */
    def userWidth: Int = masters.map(_.userWidth).max

    /** The fabric's request id width: the widest a master speaks. */
    def idWidth: Int = masters.map(_.idWidth).max

    /** The slaves' windows, in map order — what the decode walks. */
    def ranges: Seq[AddrRange] = slaves.map(_.range)

    /** The windows with side effects: what the commit trace reports as `is_mmio`. */
    def mmioRanges: Seq[AddrRange] = slaves.collect { case s if s.kind == SlaveKind.Mmio => s.range }

    def master(name: String): MasterPort =
        masters.find(_.name == name).getOrElse(throw new NoSuchElementException(unknown(name, masters.map(_.name))))

    def slave(name: String): SlavePort =
        slaves.find(_.name == name).getOrElse(throw new NoSuchElementException(unknown(name, slaves.map(_.name))))

    /** A master's port index, the position its bus lands at in the crossbar's `in` vector. */
    def masterIndex(name: String): Int = masters.indexWhere(_.name == name) match
        case -1 => throw new NoSuchElementException(unknown(name, masters.map(_.name)))
        case i  => i

    /** A slave's port index, the position its bus lands at in the crossbar's `out` vector. */
    def slaveIndex(name: String): Int = slaves.indexWhere(_.name == name) match
        case -1 => throw new NoSuchElementException(unknown(name, slaves.map(_.name)))
        case i  => i

    private def unknown(name: String, known: Seq[String]): String =
        s"no '$name' in this address map; known: ${known.mkString(", ")}"

object AddressMap:
    given upickle.default.ReadWriter[AddressMap] = upickle.default.macroRW

    /** The flag's spelling is the map's own JSON form — the one serialization the config file already uses, so there is
      * no second grammar to keep in step. Only the tests and a standalone dump pass a map on the command line.
      */
    given TokensReader.Simple[AddressMap] with
        def shortName = "map-json"

        def read(strs: Seq[String]): Either[String, AddressMap] =
            strs.lastOption.toRight("map requires a value").flatMap { text =>
                scala.util.Try(upickle.default.read[AddressMap](text)).toEither.left.map(_.getMessage)
            }

given upickle.default.ReadWriter[AddrRange] = upickle.default.macroRW

given upickle.default.ReadWriter[SlaveKind] =
    upickle.default.readwriter[String].bimap(_.toString, name => SlaveKind.valueOf(name))

given upickle.default.ReadWriter[MasterPort] = upickle.default.macroRW
