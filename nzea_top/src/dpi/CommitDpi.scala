package nzea_top.dpi

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.config.CoreConfig
import nzea_core.isa.{CsrType, Isa}
import nzea_core.middle.CommitMsg
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The commit trace's bridge to remu's `commit_trace`: one clocked call per committed instruction.
  *
  * `csr_type` is a *type*, not an address, so it is mapped back to the address the reference model knows (and to a
  * valid bit, since no CSR type means the instruction wrote no CSR). The two short fields are widened here: the ABI
  * takes `int`s, and the widths of the generated call's arguments are the declared ones.
  */
case class CommitDpiBridgeParameter(isa: Isa, defaultPc: Long) extends Parameter:
    val config: CoreConfig = CoreConfig(isa, defaultPc)

given upickle.default.ReadWriter[CommitDpiBridgeParameter] = upickle.default.macroRW

class CommitDpiBridgeLayers(parameter: CommitDpiBridgeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CommitDpiBridgeIO(parameter: CommitDpiBridgeParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val commit_msg = Flipped(ValidIO(new CommitMsg(parameter.config.width)))

class CommitDpiBridgeProbe(parameter: CommitDpiBridgeParameter)
    extends DVBundle[CommitDpiBridgeParameter, CommitDpiBridgeLayers](parameter)

@generator
object CommitDpiBridge
    extends Generator[CommitDpiBridgeParameter, CommitDpiBridgeLayers, CommitDpiBridgeIO, CommitDpiBridgeProbe]:
    override def moduleName(parameter: CommitDpiBridgeParameter): String = "CommitDpiBridge"

    def architecture(parameter: CommitDpiBridgeParameter) =
        val io = summon[Interface[CommitDpiBridgeIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val (csrAddr, csrValid) = CsrType.toImmValid(io.commit_msg.bits.csr_type)

        dpiClockedCall(
            "commit_trace",
            Some(Seq("next_pc", "csr_valid", "csr_addr", "csr_data", "gpr_addr", "gpr_data", "is_mmio"))
        )(
            io.clock,
            io.commit_msg.valid,
            io.commit_msg.bits.next_pc,
            csrValid,
            csrAddr.asBits.pad(32),
            io.commit_msg.bits.csr_data,
            io.commit_msg.bits.rd_index.asBits.pad(32),
            io.commit_msg.bits.rd_value,
            io.commit_msg.bits.is_mmio
        )
