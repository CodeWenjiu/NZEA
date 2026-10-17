package nzea_core.back

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{CsrType, Gpr}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One instruction's retire record: the write-back value, the commit-side effects, and the prediction check. It is its
  * own contract because the retire's source is not always the completion — with a load/store queue the retirement head
  * is a queue entry, which outlives the execute slot — and the middle never asks which source it came from.
  *
  * `mispredicted` is the prediction check the path already resolved (the branch unit's own `next_pc =/= pred_next_pc`),
  * carried as one bit on the result and kept per entry for the bus-issue gate to read. It is a fact about the
  * instruction, not the flush event: the event is the middle's, raised when this record retires.
  */
class RetireEvent(width: Int) extends Bundle:
    val next_pc = Aligned(UInt(width))
    val rd_index = Aligned(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))
    val csr_type = Aligned(UInt(CsrType.width))
    val csr_data = Aligned(UInt(width))
    val is_mmio = Aligned(Bool())
    val mispredicted = Aligned(Bool())

object RetireEvent:

    def of(width: Int, result: Referable[PathResult], rdIndex: Referable[UInt])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Wire[RetireEvent] =
        val event = Wire(new RetireEvent(width))
        event.next_pc := result.next_pc
        event.rd_index := rdIndex
        event.data := result.data
        event.csr_type := result.csr_type
        event.csr_data := result.csr_data
        event.is_mmio := result.is_mmio
        event.mispredicted := result.mispredict
        event
