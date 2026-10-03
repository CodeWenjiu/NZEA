package nzea_rtl.bus

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A depth-`depth` shift FIFO of `widths.size` unsigned fields: entry 0 is the head, a pop shifts every entry down one,
  * and a push lands at the tail — the slot a pop this cycle just vacated, so a pop and a push in one cycle hand over.
  *
  * It holds the entries and their count; *when* to push and pop is the caller's, which alone knows whether a bypass or
  * a same-cycle handover applies. `count` is the caller's to update through [[updateCount]], so a pop and a push in one
  * cycle net out instead of one overwriting the other. Pop before push: the pop's shift must not overwrite the entry
  * the push just wrote.
  */
private[bus] class ShiftFifo(depth: Int, widths: Seq[Int])(using
    ClockScope,
    ResetScope,
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
):
    private val cntW = log2Ceil(depth + 1)
    private val entries = widths.map(w => Seq.tabulate(depth)(_ => Reg(UInt(w))))
    private val valid = Seq.tabulate(depth)(_ => RegInit(false.B))

    val count: Reg[UInt] = RegInit(0.U(cntW))

    def nonEmpty: Referable[Bool] = valid(0)

    /** The head field `i` — meaningful only while [[nonEmpty]]. */
    def head(i: Int): Referable[UInt] = entries(i)(0)

    def pop(on: Referable[Bool]): Unit =
        when(on) {
            for i <- 0 until depth - 1 do
                valid(i) := valid(i + 1)
                for f <- widths.indices do entries(f)(i) := entries(f)(i + 1)
            valid(depth - 1) := false.B
        }

    def push(on: Referable[Bool], afterPop: Referable[Bool], values: Seq[Referable[UInt]]): Unit =
        when(on) {
            val tail = subWrap(count, afterPop ? (1.U(cntW), 0.U(cntW)), cntW)
            for i <- 0 until depth do
                when(tail === i.U(cntW)) {
                    valid(i) := true.B
                    for f <- widths.indices do entries(f)(i) := values(f)
                }
        }

    def updateCount(push: Referable[Bool], pop: Referable[Bool]): Unit =
        count := subWrap(
            addWrap(count, push ? (1.U(cntW), 0.U(cntW)), cntW),
            pop ? (1.U(cntW), 0.U(cntW)),
            cntW
        )
