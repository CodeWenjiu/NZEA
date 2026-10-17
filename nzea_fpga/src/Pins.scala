package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Analog, Bool, Record, UInt}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** A board pin a module needs the level above it to declare: the pin's own name — the XDC's, since a constraint binds
  * by name — and what the module does with it.
  *
  * The level above builds its own ports from these statements ([[Logics]]): what a logic states here is what the top
  * declares, and where its instance is named the connections are written ([[Pins.connect]]).
  */
final case class Pin(name: String, kind: PinKind)

/** What a pin is to the module that asks for it: a level it drives or reads, at the width the pin has (`Bit` is the
  * one-bit level), or the bidirectional pad of a memory bus.
  */
enum PinKind:
    case BitIn
    case BitOut
    case VectorIn(width: Int)
    case VectorOut(width: Int)
    case Pad(width: Int)

object Pin:

    def in(name: String): Pin = Pin(name, PinKind.BitIn)

    def out(name: String): Pin = Pin(name, PinKind.BitOut)

    def in(name: String, width: Int): Pin = Pin(name, PinKind.VectorIn(width))

    def out(name: String, width: Int): Pin = Pin(name, PinKind.VectorOut(width))

    /** A pin both ends drive and read — a memory bus's data and its strobes. The DSL states it with `Analog`: the two
      * ends meet through `attach`, and no `:=` touches it.
      */
    def pad(name: String, width: Int): Pin = Pin(name, PinKind.Pad(width))

object Pins:

    /** Declare `pins` as `record`'s own ports — called in the record's body, which is what a port list is built from.
      *
      * Two parts of one design may need the same pin (the board's button is the reset network's reason and the DDR3
      * device's tap calibration resets on it): identical requests are one port. One name asking for two different kinds
      * of pin in one module is refused — the port it would declare is not two ports.
      */
    def declare(record: Record, pins: Seq[Pin])(using TypeImpl): Unit =
        val declared = mutable.LinkedHashMap.empty[String, PinKind]
        for pin <- pins do
            declared
                .get(pin.name)
                .foreach: seen =>
                    require(seen == pin.kind, s"the pin '${pin.name}' is asked for as $seen and as ${pin.kind}")
            declared(pin.name) = pin.kind
        for (name, kind) <- declared do
            kind match
                case PinKind.BitIn            => record.Flipped(name, Bool())
                case PinKind.BitOut           => record.Aligned(name, Bool())
                case PinKind.VectorIn(width)  => record.Flipped(name, UInt(width))
                case PinKind.VectorOut(width) => record.Aligned(name, UInt(width))
                case PinKind.Pad(width)       => record.Aligned(name, Analog(width))

    /** Write the connections a pin statement asks for, where the instances are named: a pin the logic drives is the
      * level's own output port taking the logic's level, one it reads is its input port handed over, and a pad is the
      * level's analog end attached to the logic's.
      */
    def connect(level: Interface[?], logic: Interface[?], pins: Seq[Pin])(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line
    ): Unit =
        for pin <- pins do
            pin.kind match
                case PinKind.BitIn =>
                    logic.field[Bool](pin.name) := level.field[Bool](pin.name)
                case PinKind.BitOut =>
                    level.field[Bool](pin.name) := logic.field[Bool](pin.name)
                case PinKind.VectorIn(_) =>
                    logic.field[UInt](pin.name) := level.field[UInt](pin.name)
                case PinKind.VectorOut(_) =>
                    level.field[UInt](pin.name) := logic.field[UInt](pin.name)
                case PinKind.Pad(_) =>
                    level.field[Analog](pin.name).attach(logic.field[Analog](pin.name))
