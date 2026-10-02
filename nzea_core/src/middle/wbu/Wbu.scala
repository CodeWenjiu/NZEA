package nzea_core.middle.wbu

import java.lang.foreign.Arena

import me.jiuyang.stdlib.{Valid, ValidIO}
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.back.{BypassValue, PathResult}
import nzea_core.isa.{Gpr, Isa}
import nzea_core.middle.CommitMsg
import nzea_core.middle.rf.{ArfWrite, CsrWrite}
import nzea_core.stats.Regions
import nzea_rtl.RtlFlow
import nzea_rtl.stat.StatScope
import nzea_rtl.stdlib.*
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
    val complete = Flipped(ValidIO(new PathResult(width)))

    /** The slot's identity, from the issue stage: what the retire register latches with the completion. */
    val rd_index = Flipped(UInt(Gpr.indexWidth))

    /** The commit's write faces: the register file's write port and the CSR write, driven by the retire register. */
    val arf_write = Aligned(ValidIO(new ArfWrite(width)))
    val csr_write = Aligned(ValidIO(new CsrWrite(width)))

    /** The retire facts: the write landing this cycle (for the read's wait), and the flush the stage sources. */
    val wb_valid = Aligned(Bool())
    val wb_rd = Aligned(UInt(Gpr.indexWidth))
    val flush = Aligned(Bool())
    val redirect = Aligned(Valid(UInt(width)))
    val bypass_commit = Aligned(ValidIO(new BypassValue(width)))
    val commit = Aligned(ValidIO(new CommitMsg(width)))

class WbuProbe(parameter: WbuParameter) extends DVBundle[WbuParameter, WbuLayers](parameter)

/** The write-back stage: the retire register the completion lands in, the commit and the flush, and the two files'
  * write faces.
  *
  * EX/WB is neither elastic nor cancellable: what the units completed retires from it, the register write, the CSR
  * write and the commit ride it, and a mispredicted branch in it — `next_pc` against the prediction it was fetched
  * under — is what cancels the younger stages. The register and CSR files themselves live in the middle, where both
  * stages face them: this stage drives their write sides (`arf_write` / `csr_write`), the issue stage their reads. The
  * flush is sourced here, so every stage reads one net instead of asking the stage below.
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
        val indexWidth = Gpr.indexWidth

        // EX/WB: the one register here that is neither elastic nor cancellable — what the units completed retires from it,
        // and a mispredicted branch in it is what cancels the younger stages. The flush is sourced from it, so every stage
        // reads one net instead of asking the stage below, and the prediction check that starts the flush is made against
        // it as well: both sides of `next_pc =/= pred_next_pc` are registers here, out of the back's combinational path.
        val wb = Reg(new PathResult(width))
        val wbValid = RegInit(false.B)
        val wbRd = Reg(UInt(indexWidth))
        val complete = io.complete.valid
        val retire = wbValid
        val mispredicted = wb.is_branch & (wb.next_pc =/= wb.pred_next_pc)
        val flushing = retire & mispredicted

        /** The write-back cycle: the completion latched with the destination it wrote, then the register write, the CSR
          * write, the commit, the redirect and the retire facts the issue stage reads, all off that one register.
          */
        def retireStage(): Unit =
            when(complete) {
                wb :<= io.complete.bits
                wbRd := io.rd_index
            }
            wbValid := complete

            io.flush := flushing
            io.redirect.valid := flushing
            // The restart is wherever the branch actually resolved to: `next_pc`, not the target — under a predictor a
            // mispredict on a not-taken resolution points back at the fall-through.
            io.redirect.bits := wb.next_pc

            // The commit's write faces: the register file's write port and the CSR write.
            io.arf_write.valid := retire
            io.arf_write.bits.index := wbRd
            io.arf_write.bits.data := wb.data

            // The retiring write rides the bus back to the network as its older raw producer.
            io.bypass_commit.valid := retire
            io.bypass_commit.bits.rd_index := wbRd
            io.bypass_commit.bits.data := wb.data

            io.csr_write.valid := retire
            io.csr_write.bits.csr_type := wb.csr_type
            io.csr_write.bits.data := wb.csr_data

            io.commit.valid := retire
            io.commit.bits.next_pc := wb.next_pc
            io.commit.bits.rd_index := wbRd
            io.commit.bits.rd_value := wb.data
            io.commit.bits.is_mmio := wb.is_mmio
            io.commit.bits.csr_type := wb.csr_type
            io.commit.bits.csr_data := wb.csr_data

            io.wb_valid := retire
            io.wb_rd := wbRd

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
