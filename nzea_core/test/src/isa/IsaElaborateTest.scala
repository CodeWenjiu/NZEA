package nzea_core.isa

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

case class IsaElaborateTestParameter(dummy: Int) extends Parameter

given upickle.default.ReadWriter[IsaElaborateTestParameter] = upickle.default.macroRW

class IsaElaborateTestLayers(parameter: IsaElaborateTestParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class IsaElaborateTestIO(parameter: IsaElaborateTestParameter) extends HWBundle(parameter):
  val addr = Flipped(UInt(32))
  val csr_type = Aligned(UInt(CsrType.width))
  val csr_addr = Aligned(UInt(12))
  val csr_valid = Aligned(Bool())
  val fu_op = Flipped(UInt(FuOpWidth.Width))
  val alu_op = Aligned(UInt(AluOp.width))
  val bru_op = Aligned(UInt(BruOp.width))
  val alu_src = Aligned(UInt(AluSrc.width))
  val is_store = Aligned(Bool())
  val is_load = Aligned(Bool())

class IsaElaborateTestProbe(parameter: IsaElaborateTestParameter)
    extends DVBundle[IsaElaborateTestParameter, IsaElaborateTestLayers](parameter)

/** Exercises the hardware side of the ISA tables: address<->type mapping and the unified field slices. */
@generator
object IsaElaborateTestModule
    extends Generator[IsaElaborateTestParameter, IsaElaborateTestLayers, IsaElaborateTestIO, IsaElaborateTestProbe]:
  override def moduleName(p: IsaElaborateTestParameter): String = "IsaElaborateTest"

  def architecture(parameter: IsaElaborateTestParameter) =
    val io = summon[Interface[IsaElaborateTestIO]]

    io.csr_type := CsrType.fromAddr(io.addr)
    val (csrAddr, csrValid) = CsrType.toImmValid(io.csr_type)
    io.csr_addr := csrAddr
    io.csr_valid := csrValid

    io.alu_op := FuDecode.take(io.fu_op, AluOp.width)
    io.bru_op := FuDecode.take(io.fu_op, BruOp.width)
    io.alu_src := FuDecode.take(io.fu_op, AluSrc.width)
    io.is_store := LsuOp.isStore(FuDecode.take(io.fu_op, LsuOp.width))
    io.is_load := LsuOp.isLoad(FuDecode.take(io.fu_op, LsuOp.width))

/** Structural checks: the field widths and the CSR address map reach the lowered Verilog. */
object IsaElaborateTest extends TestSuite:

  private val module = IsaElaborateTestModule.moduleName(IsaElaborateTestParameter(0))

  private lazy val sv = TestFlow.lower(
    module,
    cfg => IsaElaborateTestModule.main(Array("config", cfg.toString, "--dummy", "0")),
    cfg => IsaElaborateTestModule.main(Array("design", cfg.toString))
  )

  /** The module's port table, so a port check cannot be satisfied by a signal of the same name inside the body. */
  private lazy val header: String =
    val start = sv.indexOf(s"module $module(")
    sv.substring(start, sv.indexOf(");", start))

  private def port(name: String, width: Int): Boolean =
    val declaration =
      if width <= 1 then raw"""(?m)(?:input|output)\s+$name\b|,\s*$name\b"""
      else raw"""\[${width - 1}:0\]\s+$name\b"""
    declaration.r.findFirstIn(header).isDefined

  val tests = Tests:
    test("module lowers with the expected port widths"):
      assert(sv.contains(s"module $module("))
      assert(port("addr", 32))
      assert(port("fu_op", FuOpWidth.Width))
      assert(port("csr_type", CsrType.width))
      assert(port("csr_addr", 12))
      assert(port("csr_valid", 1))
      assert(port("alu_op", AluOp.width))
      assert(port("bru_op", BruOp.width))
      assert(port("alu_src", AluSrc.width))
      assert(port("is_store", 1))
      assert(port("is_load", 1))

    test("CSR address map reaches the lowered logic"):
      for addr <- Seq(
          CsrType.MSTATUS_ADDR,
          CsrType.MTVEC_ADDR,
          CsrType.MSCRATCH_ADDR,
          CsrType.MEPC_ADDR,
          CsrType.MCAUSE_ADDR
        )
      do assert(sv.contains(s"12'h${addr.toHexString}"))
