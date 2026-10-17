package nzea_core.back

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.back.mem.{DbusUser, LsuData, QueueResponse}
import nzea_core.config.CoreConfig
import nzea_core.isa.CsrType

/** The sysu's offer, as the int path reports it: the CSR the held uop leaves defined, and the value it leaves there.
  *
  * The value is the CSR's post-state for every CSR op, not only the writing ones — a zero-source set or clear leaves
  * the read value — so a reader behind the uop can take it without knowing the commit's write gate, and the offer hangs
  * off the held uop and the sysu's own combination rather than off the result the commit path builds.
  */
class CsrOffer(width: Int) extends Bundle:
    val csr_type = Aligned(UInt(CsrType.width))
    val data = Aligned(UInt(width))

/** The face an execution path and the dispatch meet on, declared from the dispatch's side: the uop goes out, the report
  * comes in. The path holds the same bundle flipped, so a port added here lands on both sides at once.
  */
class IntFace(config: CoreConfig) extends Bundle:
    val issue = Aligned(new IssueSlot(new ExecSlot(config)))

    /** The completion handshake's valid: high while the uop in the slot is finished, held until the slot's pace takes
      * it — the middle's release can be deferred by the queue's room after a unit has already reported.
      */
    val done = Flipped(Bool())
    val result = Flipped(new PathResult(config.width))

    /** The CSR column's youngest writer, named by the CSR rather than by a destination index. */
    val csr_offer = Flipped(ValidIO(new CsrOffer(config.width)))

/** The integer face's three signals plus the slot's release, which only a memory access has. */
class MemFace(config: CoreConfig) extends Bundle:
    val issue = Aligned(new IssueSlot(new ExecSlot(config)))
    val done = Flipped(Bool())
    val result = Flipped(new PathResult(config.width))
    val slotRelease = Flipped(Bool())

/** The queue's face, between the memory path and the completion path. */
class QueueFace(config: CoreConfig) extends Bundle:
    val reqTag = Flipped(UInt(DbusUser.tagWidth))
    val offset = Flipped(UInt(LsuData.offsetWidth(config.width)))

    val resp = Flipped(ValidIO(new QueueResponse(config.width)))

    /** The queue's room, and whether an older mispredicted branch makes a bus access unsafe to issue. */
    val canAccept = Aligned(Bool())
    val flushRisk = Aligned(Bool())
