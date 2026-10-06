package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One producer a read can take its value from: `armed` says its value is available now, `index` the register it will
  * write and `data` the value it is producing.
  *
  * Referables, not a bundle, so a consumer points at wherever the signals live — a module port, a register — without a
  * copy. A producer whose value can still be missing is not a source here; it arrives as an unarmed offer, which is the
  * other half of a wakeup ([[ForwardOffer]]).
  */
final case class ForwardSource(armed: Referable[Bool], index: Referable[UInt], data: Referable[UInt])

/** One in-flight writer as a forward network broadcasts it: `valid` says the offer is a writer (a live producer naming
  * a register — never x0), `armed` says its value is available now, `index` and `data` are what it writes.
  *
  * `valid & !armed` is a writer whose value is not there yet: a read that names it must hold for it. The two bits are
  * the whole wakeup — a producer either answers a read or keeps it waiting — and the policy of which stages forward is
  * already folded into `armed`, so a consumer matches without knowing the configuration.
  */
class ForwardOffer(width: Int, indexWidth: Int) extends Bundle:
    val valid = Aligned(Bool())
    val armed = Aligned(Bool())
    val index = Aligned(UInt(indexWidth))
    val data = Aligned(UInt(width))

/** A read's answer, as the consumer resolves it: whether the read may move this cycle (`ready`), whether a producer's
  * value is the one to take — the register file's answer is the one otherwise (`from_net`) — and the value itself.
  *
  * The wait is folded into `ready`: whoever resolves a read reads one verdict, move or hold, and never a wait term of
  * its own; `from_net` only picks between the two values once the read moves.
  */
final case class Wake(ready: Referable[Bool], from_net: Referable[Bool], value: Referable[UInt])

/** The read network: a read that names what a source is writing takes that source's value, and one that names nothing
  * keeps the value it read — the register file's own.
  *
  * Several sources can match at once when the configuration forwards from more than one stage, so the sequence's order
  * is the priority: newest first, the order the producers retire in. An empty sequence is the strictest configuration
  * and emits nothing, which is what lets a configuration without a link cost no logic at all.
  */
def forwardRead(
    index: Referable[UInt],
    from: Referable[UInt],
    sources: Seq[ForwardSource]
)(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Referable[UInt] =
    sources.foldRight(from: Referable[UInt]) { (source, earliest) =>
        (source.armed & (source.index === index)) ? (source.data, earliest)
    }
