package nzea_fpga

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.{Bool, Clock, Record, Reset}
import nzea_fpga.ip.ClockOut
import nzea_rtl.stdlib.Resets
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The reset domain a logic runs in, as the logic states it: the domain's name, the grid its synchronizer runs on, and
  * the reasons it waits for — the level above's own vocabulary plus the names of the reasons the logic itself produces
  * ([[LogicNeeds.produces]]).
  *
  * What crosses into the logic is the reset itself, not the domain: the synchronizer and the conjunction are stated
  * where the level's network is wired, which is where its reasons are produced ([[nzea_rtl.stdlib.Resets]]), and what
  * arrives is the reset whose edges that network made belong to this domain.
  */
final case class LogicReset(name: String, clock: ClockOut, terms: Seq[String])

/** What a logic module needs from the level above it: the board's pins it drives and reads ([[Pin]]), the clock grids
  * its instances run on, the reset reasons it produces for the level's network, and the domain its registers run in.
  *
  * Stated before any body runs, because a port list is built from it — the level's own ports from the pins, the logic's
  * from everything ([[Logics]]).
  */
final case class LogicNeeds(
    pins: Seq[Pin],
    grids: Seq[ClockOut],
    produces: Seq[String],
    reset: Option[LogicReset]
)

/** A module the level above carries: it states what it needs once ([[LogicNeeds]]), and the level both declares the
  * ports that asks for and writes the connections (`Logics.declare`/`Logics.connect`).
  *
  * This is what lets a logic own a chip: the logic instantiates the chip itself and names the chip's pins here, so the
  * boundary declares the pins without knowing whose they are.
  */
trait BoardLogic:
    def needs: LogicNeeds

object Logics:

    /** The logic's port the level hands its reset on ([[LogicNeeds.reset]]). */
    val ResetPort = "resetN"

    /** Declare a logic's needs as its own ports — called in the logic's body, which is what its port list is built
      * from: the pins ([[Pins.declare]]), a clock port per grid, a port per reason it produces, and its reset.
      */
    def declare(record: Record, needs: LogicNeeds)(using TypeImpl): Unit =
        Pins.declare(record, needs.pins)
        Clocks.declare(record, needs.grids)
        for reason <- needs.produces do record.Aligned(reason, Bool())
        needs.reset.foreach(_ => record.Flipped(ResetPort, Reset()))

    /** Satisfy a logic's needs where its instance is named, in the level above: its pins from the level's own ports, a
      * wire per grid from the level's own plan, a term and a domain in the level's own reset network — the reasons the
      * domain names exist there, which is why the domain is stated there rather than inside the logic.
      */
    def connect(level: Interface[?], logic: Interface[?], needs: LogicNeeds)(using
        Arena,
        Context,
        Block,
        TypeImpl,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        Pins.connect(level, logic, needs.pins)
        Clocks.connect(logic, needs.grids)
        for reason <- needs.produces do Resets.term(reason)(logic.field[Bool](reason))
        for reset <- needs.reset do
            logic.field[Reset](ResetPort) := Resets.domain(reset.name, Clocks.clock(reset.clock), reset.terms)
