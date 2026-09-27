package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One producer a read can take its value from before it reaches the register file: `armed` says an instruction in
  * flight will write `index`, `data` is the value it is producing.
  *
  * Referables, not a bundle, so a consumer points at wherever the signals live — a module port, a register — without a
  * copy. Every source here is ready in the cycle it is armed; a producer whose value can still be missing (a load
  * waiting on the bus) arrives with the stage that produces it and with the wait output that keeps a consumer off it.
  */
final case class ForwardSource(armed: Referable[Bool], index: Referable[UInt], data: Referable[UInt])

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
