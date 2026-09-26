package nzea_rtl

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

/** Toolchain spike: proves the zaozi frontend can produce the artefacts the rewrite needs.
  *
  * Covered here: a parameterised generator, a nested generator instance, registers with explicit clock/reset scopes,
  * `when`/`otherwise`, an external module whose Verilog body we ship ourselves (the `StatsRegs` pattern), and a stable
  * `moduleName` so testbenches and file lists can rely on it.
  */
case class PocAddParameter(width: Int) extends Parameter:
  require(width >= 1 && width <= 64, s"width must be 1..64, got $width")

given upickle.default.ReadWriter[PocAddParameter] = upickle.default.macroRW

class PocAddLayers(parameter: PocAddParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PocAddIO(parameter: PocAddParameter) extends HWBundle(parameter):
  val a = Flipped(Bits(parameter.width))
  val b = Flipped(Bits(parameter.width))
  val s = Aligned(Bits(parameter.width))

class PocAddProbe(parameter: PocAddParameter) extends DVBundle[PocAddParameter, PocAddLayers](parameter)

@generator
object PocAdd extends Generator[PocAddParameter, PocAddLayers, PocAddIO, PocAddProbe]:
  override def moduleName(p: PocAddParameter): String = s"PocAdd_w${p.width}"

  def architecture(parameter: PocAddParameter) =
    val io = summon[Interface[PocAddIO]]
    io.s := (io.a.asUInt + io.b.asUInt).asBits.bits(parameter.width - 1, 0)

case class PocCounterParameter(width: Int) extends Parameter:
  require(width >= 1 && width <= 32, s"width must be 1..32, got $width")

given upickle.default.ReadWriter[PocCounterParameter] = upickle.default.macroRW

class PocCounterLayers(parameter: PocCounterParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PocCounterIO(parameter: PocCounterParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val incr = Flipped(Bool())
  val value = Aligned(Bits(32))

class PocCounterProbe(parameter: PocCounterParameter) extends DVBundle[PocCounterParameter, PocCounterLayers](parameter)

case class PocCounterVerilogParams(width: Int) extends VerilogParameter

/** Register with a VPI-readable leaf name, declared in Verilog we supply (PocEmit writes the file). */
@generator
object PocCounter
    extends VerilogWrapper[
      PocCounterParameter,
      PocCounterLayers,
      PocCounterIO,
      PocCounterProbe,
      PocCounterVerilogParams
    ]:
  def verilogModuleName(parameter: PocCounterParameter) = s"PocCounter_w${parameter.width}"
  def verilogParameter(parameter: PocCounterParameter) = PocCounterVerilogParams(parameter.width)

case class PocParameter(width: Int, depth: Int) extends Parameter:
  require(width >= 1 && width <= 32, s"width must be 1..32, got $width")
  require(depth >= 1, s"depth must be positive, got $depth")

given upickle.default.ReadWriter[PocParameter] = upickle.default.macroRW

class PocLayers(parameter: PocParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class PocIO(parameter: PocParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val resetN = Flipped(Reset())
  val in = Flipped(Bits(parameter.width))
  val sel = Flipped(Bool())
  val sum = Aligned(Bits(parameter.width))
  val count = Aligned(Bits(32))

class PocProbe(parameter: PocParameter) extends DVBundle[PocParameter, PocLayers](parameter)

@generator
object Poc extends Generator[PocParameter, PocLayers, PocIO, PocProbe]:
  override def moduleName(p: PocParameter): String = s"Poc_w${p.width}_d${p.depth}"

  def architecture(parameter: PocParameter) =
    val io = summon[Interface[PocIO]]
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveLow(io.resetN)

    val adder = PocAdd.instantiate(PocAddParameter(parameter.width))
    adder.io.a := io.in
    adder.io.b := io.in
    val doubled = adder.io.s

    val acc = RegInit(0.U(parameter.width))
    when(io.sel) {
      acc := doubled.asUInt
    }.otherwise {
      acc := (acc.asBits.asUInt + 1.U(parameter.width)).asBits.bits(parameter.width - 1, 0).asUInt
    }

    val hit = acc.asBits === io.in
    val counter = PocCounter.instantiate(PocCounterParameter(parameter.width))
    counter.io.clock := io.clock
    counter.io.resetN := io.resetN
    counter.io.incr := hit

    io.sum := acc.asBits
    io.count := counter.io.value
