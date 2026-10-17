package nzea_core.middle.rf

import java.lang.foreign.Arena

import me.jiuyang.stdlib.ValidIO
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_core.isa.CsrType
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

class CsrWrite(width: Int) extends Bundle:
    val csr_type = Aligned(UInt(CsrType.width))
    val data = Aligned(UInt(width))

class CsrReadPort(width: Int) extends Bundle:
    val code = Flipped(UInt(CsrType.width))
    val data = Aligned(UInt(width))

case class CsrFileParameter(width: Int) extends Parameter

given upickle.default.ReadWriter[CsrFileParameter] = upickle.default.macroRW

class CsrFileLayers(parameter: CsrFileParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

class CsrFileIO(parameter: CsrFileParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())
    val write = Flipped(ValidIO(new CsrWrite(parameter.width)))
    val read = Aligned(new CsrReadPort(parameter.width))

class CsrFileProbe(parameter: CsrFileParameter) extends DVBundle[CsrFileParameter, CsrFileLayers](parameter)

/** Machine-mode CSRs: written at commit, read combinationally by code while the instruction issues. The address→code
  * mapping is the level's above ([[Rf]]), so the file reads what the reader resolved.
  *
  * Storage only, like the register file: a read answers what the file holds, and the write lands at the end of its
  * cycle. Anything younger — the write the retiring instruction makes this cycle, the writer still in flight — is the
  * forwarding network's to offer, not this file's.
  *
  * `mstatus` resets to 0x1800 (matching the legacy core: MPP = M), the rest to zero. A code that names no implemented
  * CSR reads as zero, and a write to one is dropped — the baseline has no illegal-CSR trap.
  */
@generator
object CsrFile extends Generator[CsrFileParameter, CsrFileLayers, CsrFileIO, CsrFileProbe]:
    override def moduleName(parameter: CsrFileParameter): String = "CsrFile"

    def architecture(parameter: CsrFileParameter) =
        val io = summon[Interface[CsrFileIO]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.syncActiveLow(io.resetN)

        val width = parameter.width
        val mstatus = RegInit(0x1800.U(width))
        val mtvec = RegInit(0.U(width))
        val mepc = RegInit(0.U(width))
        val mcause = RegInit(0.U(width))
        val mscratch = RegInit(0.U(width))

        when(io.write.valid & (io.write.bits.csr_type =/= CsrType.None.lit.U(CsrType.width))) {
            when(io.write.bits.csr_type === CsrType.Mstatus.lit.U(CsrType.width)) { mstatus := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mtvec.lit.U(CsrType.width)) { mtvec := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mepc.lit.U(CsrType.width)) { mepc := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mcause.lit.U(CsrType.width)) { mcause := io.write.bits.data }
            when(io.write.bits.csr_type === CsrType.Mscratch.lit.U(CsrType.width)) { mscratch := io.write.bits.data }
        }

        val stored: Referable[UInt] = muxCase(
            0.U(width),
            Seq(
                (io.read.code === CsrType.Mstatus.lit.U(CsrType.width), mstatus),
                (io.read.code === CsrType.Mtvec.lit.U(CsrType.width), mtvec),
                (io.read.code === CsrType.Mepc.lit.U(CsrType.width), mepc),
                (io.read.code === CsrType.Mcause.lit.U(CsrType.width), mcause),
                (io.read.code === CsrType.Mscratch.lit.U(CsrType.width), mscratch)
            )
        )
        io.read.data := stored
