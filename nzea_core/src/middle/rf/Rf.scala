package nzea_core.middle.rf

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.{CsrType, Gpr}
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** One GPR read port as the reader sees it: the file's own read, plus what this cycle's commit leaves on it — whether
  * the write port names the index, and the value the reader takes (the file's read, write-through).
  */
class ArfPort(width: Int) extends Bundle:
    val index = Flipped(UInt(Gpr.indexWidth))
    val data = Aligned(UInt(width))
    val commit_hit = Aligned(Bool())

class CsrPort(width: Int) extends Bundle:
    val addr = Flipped(UInt(12))
    val code = Aligned(UInt(CsrType.width))
    val data = Aligned(UInt(width))
    val commit_hit = Aligned(Bool())

case class RfParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[RfParameter] = upickle.default.macroRW

class RfLayers(parameter: RfParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class RfIO(parameter: RfParameter) extends HWBundle(parameter):
    private val width = parameter.width
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

    val arf_write = Flipped(ValidIO(new ArfWrite(width)))
    val csr_write = Flipped(ValidIO(new CsrWrite(width)))

    /** The reader's read faces: the register file's two ports — rs1, then rs2 — and the CSR's one. */
    val arf_read = Aligned(StaticVec[ArfPort, 2](new ArfPort(width)))
    val csr_read = Aligned(new CsrPort(width))

class RfProbe(parameter: RfParameter) extends DVBundle[RfParameter, RfLayers](parameter)

/** The architectural state: [[Arf]] and [[CsrFile]], and what a reader sees of this cycle's commit.
  *
  * The two files are storage — a read answers what they hold, and a write lands at the end of its cycle — and what this
  * level adds is the one thing both a reader and the commit touch at once: the write port's own cycle. A read that
  * names the index (or code) the commit is writing takes the commit's value (write-through), and the port's
  * `commit_hit` says so, so a reader applies the level's verdict — the network's `commit_armed` — to a fact instead of
  * rebuilding the comparison. x0 is never written and `None` never names a CSR, so neither can hit.
  *
  * The CSR's address maps to its code once, here: the reader names the address, the file reads by the code, and both
  * the reader and the commit compare codes. What is younger than the file — the completing instruction, the writers
  * still in flight — is the forward network's, not this module's.
  */
@generator
object Rf extends Generator[RfParameter, RfLayers, RfIO, RfProbe]:
    override def moduleName(parameter: RfParameter): String = "Rf"

    def architecture(parameter: RfParameter) =
        val io = summon[Interface[RfIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.width

        val arf = Arf.instantiate(ArfParameter(width))
        val csr = CsrFile.instantiate(CsrFileParameter(width))

        arf.io.clock := io.clock
        arf.io.resetN := io.resetN
        csr.io.clock := io.clock
        csr.io.resetN := io.resetN

        arf.io.write :<= io.arf_write
        csr.io.write :<= io.csr_write

        def gpr(port: Referable[ArfPort], file: Referable[ArfReadPort]): Unit =
            file.index := port.index
            val hit =
                io.arf_write.valid & (io.arf_write.bits.index =/= 0.U(Gpr.indexWidth)) &
                    (io.arf_write.bits.index === port.index)
            port.commit_hit := hit
            port.data := hit ? (io.arf_write.bits.data, file.data)

        gpr(io.arf_read.ref(0), arf.io.rs1)
        gpr(io.arf_read.ref(1), arf.io.rs2)

        val code: Node[UInt] = CsrType.fromAddr(io.csr_read.addr)
        io.csr_read.code := code
        csr.io.read.code := code
        val csrHit =
            io.csr_write.valid & (io.csr_write.bits.csr_type =/= CsrType.None.lit.U(CsrType.width)) &
                (io.csr_write.bits.csr_type === code)
        io.csr_read.commit_hit := csrHit
        io.csr_read.data := csrHit ? (io.csr_write.bits.data, csr.io.read.data)
