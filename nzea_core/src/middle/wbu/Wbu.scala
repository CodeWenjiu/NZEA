package nzea_core.middle.wbu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Decoupled, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.RetireEvent
import nzea_core.isa.{CsrType, Gpr, Isa}
import nzea_core.middle.rf.{ArfWrite, CsrWrite}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
import nzea_sideband.CommitTrace
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

case class WbuParameter(isa: Isa, defaultPc: Long, flow: RtlFlow, stats: StatScope) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[WbuParameter] = upickle.default.macroRW

class WbuLayers(parameter: WbuParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class WbuIO(parameter: WbuParameter) extends HWBundle(parameter):
    private val width = parameter.config.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    /** The retiring instruction, in program order, as the write-back register presents it: the completion without a
      * queue, the queue's head with one.
      */
    val retire = Flipped(Decoupled(new RetireEvent(width)))

    /** The commit's write faces: the register file's write port and the CSR write, driven by the retire register. */
    val arf_write = Aligned(ValidIO(new ArfWrite(width)))
    val csr_write = Aligned(ValidIO(new CsrWrite(width)))

    /** The retire facts: the flush event the stage sources — one bus the level tops broadcast: `valid` kills the
      * younger stages, `next_pc` restarts the front.
      */
    val flush = Aligned(new FlushBus(width))
    val commit = Aligned(ValidIO(new CommitTrace(width, Gpr.indexWidth, CsrType.addrWidth)))

class WbuProbe(parameter: WbuParameter) extends DVBundle[WbuParameter, WbuLayers](parameter)

/** The write-back stage's logic: the commit and the flush event, and the two files' write faces — combinational, behind
  * the register the middle declares for this boundary.
  *
  * EX/WB is the one boundary whose register does not obey the flush ([[nzea_rtl.stdlib.FlushHandover.Ignore]]): the
  * record this stage presents is the one that raises it, and it must commit in the very cycle it does. The record is
  * the back's [[RetireEvent]] — the completion's, or a queue entry's — so this stage does not know which instruction
  * source it is retiring from; the prediction check rides the record too (`mispredicted`), made where the record was
  * written. What this stage owns is the event: `flushing` is raised the cycle the record is in write-back, and the same
  * pulse and the record's `next_pc` make the one [[FlushBus]] the level tops broadcast. The register and CSR files
  * themselves live in the middle, where both stages face them: this stage drives their write sides (`arf_write` /
  * `csr_write`), the issue stage their reads. The flush is sourced here, so every stage reads one net instead of asking
  * the stage below.
  *
  * The counters the write-back side owns — `cycle`, `inst_commit`, `flush` and the `ipc` rule over them — are declared
  * here, where the commit and the cycle are both known.
  */
@generator
object Wbu extends Generator[WbuParameter, WbuLayers, WbuIO, WbuProbe]:
    override def moduleName(parameter: WbuParameter): String = "Wbu"

    def architecture(parameter: WbuParameter) =
        val io = summon[Interface[WbuIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.config.width

        // EX/WB: the register is the middle's, declared beside the stage's other boundaries — what sits here is the
        // logic behind it. This stage always consumes what the register presents (the commit is one instruction per
        // cycle and never stalls), so the handshake's consumer side is tied high for now: the port is where a real pace
        // would live the day the stage has one. `flushing` is the one fact the stage adds to the record: the pulse that
        // cancels the younger stages, raised the cycle the record is in write-back.
        io.retire.ready := true.B
        val retire = io.retire.valid
        val wb = io.retire.bits
        val flushing = retire & wb.mispredicted

        /** The write-back cycle: the record the register presents, turned into the register write, the CSR write, the
          * commit, the flush event and the retire facts the issue stage reads.
          */
        def retireStage(): Unit =
            io.flush.valid := flushing
            // The restart is wherever the branch actually resolved to: `next_pc`, not the target — under a predictor a
            // mispredict on a not-taken resolution points back at the fall-through.
            io.flush.next_pc := wb.next_pc

            // The commit's write faces: the register file's write port and the CSR write.
            io.arf_write.valid := retire
            io.arf_write.bits.index := wb.rd_index
            io.arf_write.bits.data := wb.data

            io.csr_write.valid := retire
            io.csr_write.bits.csr_type := wb.csr_type
            io.csr_write.bits.data := wb.csr_data

            io.commit.valid := retire
            io.commit.bits.next_pc := wb.next_pc
            io.commit.bits.rd_index := wb.rd_index
            io.commit.bits.rd_value := wb.data
            io.commit.bits.is_mmio := wb.is_mmio
            // The trace reports the CSR as an architectural fact: resolve the internal type to `(addr, valid)` here,
            // so no consumer of the commit bus has to know `CsrType`.
            val (csrAddr, csrValid) = CsrType.toImmValid(wb.csr_type)
            io.commit.bits.csr_valid := csrValid
            io.commit.bits.csr_addr := csrAddr
            io.commit.bits.csr_data := wb.csr_data

        /** Counters the simulator reads back: declared here, where the commit and the cycle are both known, and emitted
          * from this one call — a dump reads the registered set back out after elaboration.
          */
        def counters(): Unit =
            val cycle = parameter.stats.stat("cycle", Regions.Throughput, "core clock cycles")
            val commit = parameter.stats.stat("inst_commit", Regions.Throughput, "retired instructions")
            val flush = parameter.stats.stat("flush", Regions.Throughput, "cycles a flush drains the younger stages")
            cycle.inc(true.B)
            commit.inc(retire)
            flush.inc(flushing)
            parameter.stats.rule(
                "ipc",
                s"${commit.name} / ${cycle.name}",
                Regions.Throughput,
                "",
                4,
                "instructions per cycle"
            )

        retireStage()
        if parameter.flow.counters then counters()
