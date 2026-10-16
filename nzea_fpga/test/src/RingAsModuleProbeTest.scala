package nzea_fpga

import java.lang.foreign.Arena

import utest.*

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.{Elaborate, TestFlow}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** The probe's own modules: their parameters are fresh in any JVM, and a generator dumps its module once per parameter
  * per JVM — a module with parameters another suite has already elaborated is served from that dump, and there is then
  * nothing left to link.
  */
private case class ProbeParameter(marker: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeParameter] = upickle.default.macroRW

private class ProbeLayers(parameter: ProbeParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class ProbeIO(parameter: ProbeParameter) extends HWBundle(parameter):
    val i = Flipped(Bool())
    val o = Aligned(Bool())

private class ProbeProbe(parameter: ProbeParameter) extends DVBundle[ProbeParameter, ProbeLayers](parameter)

@generator
private object Probe extends Generator[ProbeParameter, ProbeLayers, ProbeIO, ProbeProbe]:
    override def moduleName(parameter: ProbeParameter): String = s"Probe_${parameter.marker}"

    def architecture(parameter: ProbeParameter) =
        val io = summon[Interface[ProbeIO]]
        io.o := io.i

/** A wrapper of the probe's own: a module whose body is *text*, the shape every pads module is. */
private case class ProbeWrapperParameter(marker: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeWrapperParameter] = upickle.default.macroRW

private class ProbeWrapperLayers(parameter: ProbeWrapperParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class ProbeWrapperIO(parameter: ProbeWrapperParameter) extends HWBundle(parameter):
    val i = Flipped(Bool())
    val o = Aligned(Bool())

private class ProbeWrapperProbe(parameter: ProbeWrapperParameter)
    extends DVBundle[ProbeWrapperParameter, ProbeWrapperLayers](parameter)

private case class ProbeWrapperVerilogParameter() extends VerilogParameter

@generator
private object ProbeWrapper
    extends VerilogWrapper[
        ProbeWrapperParameter,
        ProbeWrapperLayers,
        ProbeWrapperIO,
        ProbeWrapperProbe,
        ProbeWrapperVerilogParameter
    ]:

    override def moduleName(parameter: ProbeWrapperParameter): String = s"ProbeWrapper_${parameter.marker}"

    override def verilogModuleName(parameter: ProbeWrapperParameter): String = s"ProbeWrapper_${parameter.marker}"

    def verilogParameter(parameter: ProbeWrapperParameter): ProbeWrapperVerilogParameter =
        ProbeWrapperVerilogParameter()

/** What the lowering does with a hand-written module of the circuit: what is emitted beside it, what it itself is.
  *
  * The question behind these probes: could a module whose body is text — a pad's I/O cells, say — be a module *of the
  * circuit* (a `VerilogWrapper`, the shape every text-body module here has) instead of a file bolted on beside the
  * dump? Three facts decided it, and this pins them:
  *
  *   1. a module of the circuit that no instance points at is *not* pruned — it is lowered, dumped and listed like any
  *      other, so a text-only module would survive a root whose reference to it is only text; 2. an extmodule (a
  *      wrapper) as the *base* of the circuit lowers without complaint, and **no file is emitted for it** — an
  *      `ExtModule` has no body, so the module's text and its file-list entry stay the writer's business, wrapper or
  *      not ([[nzea_fpga.device.ddr3.Ddr3Pads]] does exactly that today); 3. a wrapper reaches a circuit only by being
  *      *instantiated* (its own `main` is generated for a `Generator` and dies with a `ClassCastException`, so there is
  *      no `design` step to elaborate one as a root on its own).
  *
  * A root *wrapper* also needs typed pads, which is what `Analog` is for — and that is in zaozi's constructor surface
  * now ([[me.jiuyang.zaozi.ConstructorApi]] has `Analog`, and `attach` is how such a net is joined to a pin:
  * [[nzea_fpga.device.ddr3.Ddr3Pads]] is what that made possible — the DDR3 pads are the top's own ports). These probes
  * are the first three facts; the fourth is the pads themselves.
  */
object RingAsModuleProbeTest extends TestSuite:

    /** A dump of a two-module circuit: the modules it lowered to stdout, the files the split dump wrote, and its list.
      * One elaboration per case, because a generator dumps its module once per parameter per JVM.
      */
    private def dump(moduleName: String, writeConfig: os.Path => Unit, design: os.Path => Unit) =
        val scratch = os.temp.dir(prefix = "nzea-probe")
        try
            val svDir = scratch / "sv"
            // A stale probe module from an earlier run would count as pre-existing to `withLinked`'s working-directory
            // diffing, and the run would then look as if it had produced nothing.
            os.list(os.pwd)
                .filter(path => path.ext == "mlirbc" && path.last.startsWith("Probe"))
                .foreach(os.remove(_))
            Elaborate.withLinked(moduleName, scratch, writeConfig, design): circuit =>
                val module = """module\s+(\w+)\(""".r
                val modules = module
                    .findAllMatchIn(Elaborate.verilog(circuit, TestFlow.loweringOptions))
                    .map(_.group(1))
                    .toSeq
                Elaborate.verilogInto(circuit, svDir, "--split-verilog" +: TestFlow.loweringOptions)
                (modules, os.list(svDir).map(_.last).sorted.toSeq, os.read(svDir / "filelist.f").linesIterator.toSeq)
        finally os.remove.all(scratch)

    val tests = Tests:

        test("a module of the circuit that nothing instantiates is still lowered and dumped"):
            val cfgDir = os.temp.dir(prefix = "nzea-probe-sibling")
            try
                val siblingConfig = (cfgDir / "sibling.json").toString
                val (modules, files, list) = dump(
                    Probe.moduleName(ProbeParameter(1)),
                    cfg => Probe.main(Array("config", cfg.toString, "--marker", "1")),
                    cfg =>
                        Probe.main(Array("design", cfg.toString))
                        // The sibling: the position a second module of the dump would be in.
                        Probe.main(Array("config", siblingConfig, "--marker", "2"))
                        Probe.main(Array("design", siblingConfig))
                )
                assert(modules.contains("Probe_1"))
                assert(modules.contains("Probe_2"))
                assert(files.contains("Probe_2.sv"))
                assert(list.contains("Probe_2.sv"))
            finally os.remove.all(cfgDir)

        test("an extmodule as the base of the circuit lowers, and writes no file of its own"):
            val (modules, files, list) = dump(
                ProbeWrapper.moduleName(ProbeWrapperParameter(11)),
                cfg => ProbeUser.main(Array("config", cfg.toString, "--marker", "12")),
                cfg => ProbeUser.main(Array("design", cfg.toString))
            )
            // The instance is what puts the wrapper's circuit in the dump — and the module beside it is dumped.
            assert(modules.contains("ProbeUser_12"))
            assert(files.contains("ProbeUser_12.sv"))
            assert(list.contains("ProbeUser_12.sv"))
            // The extmodule itself has no body to emit: its text, and its entry in the list, are the writer's.
            assert(!files.contains(s"ProbeWrapper_11.sv"))
            assert(!list.contains(s"ProbeWrapper_11.sv"))

/** The probe's parent: it instantiates the wrapper, which is the only way a wrapper's circuit reaches a dump. */
@generator
private object ProbeUser extends Generator[ProbeParameter, ProbeLayers, ProbeIO, ProbeProbe]:
    override def moduleName(parameter: ProbeParameter): String = s"ProbeUser_${parameter.marker}"

    def architecture(parameter: ProbeParameter) =
        val io = summon[Interface[ProbeIO]]
        val wrapper = ProbeWrapper.instantiate(ProbeWrapperParameter(11))
        wrapper.io.i := io.i
        io.o := wrapper.io.o
