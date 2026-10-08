package nzea_core.back

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.mem.{DbusUser, LsqResponse, LsuData}
import nzea_core.config.CoreConfig

/** The face an execution path and the middle meet on: the uop gated to the path, and the report the path sends back.
  *
  * Declared from the middle's side — the uop goes out, the report comes in — and the path holds the same bundle
  * flipped, so the two ends are one declaration: a port added here lands on both sides at once, and neither end can
  * drift.
  */
class IntFace(config: CoreConfig) extends Bundle:
    val issue = Aligned(new IssueSlot(new IssueUop(config)))

    /** The completion handshake's valid: high while the uop in the slot is finished, held until the slot's pace takes
      * it — the middle's release can be deferred by the queue's room after a unit has already reported.
      */
    val done = Flipped(Bool())
    val result = Flipped(new PathResult(config.width))

/** The memory path's execution face: the integer face's three signals plus the release, which only a memory access has
  * — the slot's uop is finished with the slot when its request is accepted, a load's answer still in flight.
  */
class MemFace(config: CoreConfig) extends Bundle:
    val issue = Aligned(new IssueSlot(new IssueUop(config)))
    val done = Flipped(Bool())
    val result = Flipped(new PathResult(config.width))
    val slotRelease = Flipped(Bool())

/** The queue's face, between the memory path and the completion path: what a new entry records about a request, and the
  * verdicts that gate the request.
  */
class QueueFace(config: CoreConfig) extends Bundle:
    /** The tag the request's answer will carry, and the in-word offset a load's value is selected with. */
    val reqTag = Flipped(UInt(DbusUser.tagWidth))
    val offset = Flipped(UInt(LsuData.offsetWidth(config.width)))

    /** The bus's answers, re-exported for the queue's tag match. */
    val resp = Flipped(ValidIO(new LsqResponse(config.width)))

    /** The queue's room, and whether an older mispredicted branch makes a bus access unsafe to issue. */
    val canAccept = Aligned(Bool())
    val flushRisk = Aligned(Bool())
