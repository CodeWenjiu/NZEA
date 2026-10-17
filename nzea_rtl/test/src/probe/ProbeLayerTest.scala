package nzea_rtl.probe

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import nzea_rtl.{Elaborate, TestFlow}
import utest.*

/** The cross-hierarchy observation the ILA wants: a level top reads a signal two levels down through a probe, so a debug
  * signal does not have to be threaded up as a port at every level.
  *
  * The shape, and the reason it is this shape:
  *
  *   - the module that owns the signal declares a probe field for it and binds it in a layer block (it can only be sent
  *     from the layer's colour);
  *   - a level in between re-exports it — a probe is named on the interface of the module that sends it, so it crosses
  *     one boundary per declaration;
  *   - the consumer is instantiated *inside* a layer block, because a layer block can read the enclosing scope but
  *     cannot drive it, so a resolved probe cannot leave the layer for an outside instance to use.
  *
  * The lowering is firtool's `bind` machinery: one layer module per (module, layer) holds the blocks, and the references
  * across levels become hierarchical names — which is what makes the deep signal reachable at all.
  *
  * One limit is worth stating with the mechanism, because it is silent: **Vivado synthesis drops `bind`** — the bound
  * module never reaches the netlist, and a layer-hosted ILA disappears from the build with no warning (verified with a
  * minimal `dont_touch`-marked tap that came out of `synth_design` as nothing). So this is the mechanism for tools that
  * honor `bind` (simulators, verifiers); on a Vivado board, an instrument that has to survive synthesis stays outside
  * the layer world, and reaching a deep signal is the netlist debug flow's business (`create_debug_core` on kept nets).
  */

/** The one thing producer, middle and consumer have to agree on: the layer a debug probe lives in. */
private object DebugLayer:
    val layers: Seq[Layer] = Seq(Layer("debug"))

private case class ProbeLayerSensorParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeLayerSensorParameter] = upickle.default.macroRW

private class ProbeLayerSensorLayers(parameter: ProbeLayerSensorParameter) extends LayerInterface(parameter):
    def layers = DebugLayer.layers

private class ProbeLayerSensorIO(parameter: ProbeLayerSensorParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

private class ProbeLayerSensorProbe(parameter: ProbeLayerSensorParameter)
    extends DVBundle[ProbeLayerSensorParameter, ProbeLayerSensorLayers](parameter):
    val count = ProbeRead(UInt(parameter.width), layers("debug"))

@generator
private object ProbeLayerSensor
    extends Generator[ProbeLayerSensorParameter, ProbeLayerSensorLayers, ProbeLayerSensorIO, ProbeLayerSensorProbe]:
    override def moduleName(parameter: ProbeLayerSensorParameter): String = "ProbeLayerSensor"

    def architecture(parameter: ProbeLayerSensorParameter) =
        val io = summon[Interface[ProbeLayerSensorIO]]
        val probe = summon[ProbeInterface[ProbeLayerSensorProbe]]
        given ClockScope = ClockScope.posedge(io.clock)
        given ResetScope = ResetScope.asyncActiveLow(io.resetN)

        val count = RegInit(0.U(parameter.width))
        count := constAdder(count, 1, parameter.width)
        layer("debug"):
            probe.count <== count

private case class ProbeLayerMidParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeLayerMidParameter] = upickle.default.macroRW

private class ProbeLayerMidLayers(parameter: ProbeLayerMidParameter) extends LayerInterface(parameter):
    def layers = DebugLayer.layers

private class ProbeLayerMidIO(parameter: ProbeLayerMidParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

private class ProbeLayerMidProbe(parameter: ProbeLayerMidParameter)
    extends DVBundle[ProbeLayerMidParameter, ProbeLayerMidLayers](parameter):
    val count = ProbeRead(UInt(parameter.width), layers("debug"))

@generator
private object ProbeLayerMid
    extends Generator[ProbeLayerMidParameter, ProbeLayerMidLayers, ProbeLayerMidIO, ProbeLayerMidProbe]:
    override def moduleName(parameter: ProbeLayerMidParameter): String = "ProbeLayerMid"

    def architecture(parameter: ProbeLayerMidParameter) =
        val io = summon[Interface[ProbeLayerMidIO]]
        val probe = summon[ProbeInterface[ProbeLayerMidProbe]]

        val sensor = ProbeLayerSensor.instantiate(ProbeLayerSensorParameter(parameter.width))
        sensor.io.clock := io.clock
        sensor.io.resetN := io.resetN

        layer("debug"):
            val relay = Wire(UInt(parameter.width))
            relay <== sensor.probe.count
            probe.count <== relay

// The consumer: what a bound observation is from the RTL side — an instance the layer module hosts. It is a black box
// (the real one is the ILA), which is also why it survives: a plain module with nothing to drive would be dead.

private case class ProbeLayerSinkParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeLayerSinkParameter] = upickle.default.macroRW

private class ProbeLayerSinkLayers(parameter: ProbeLayerSinkParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class ProbeLayerSinkIO(parameter: ProbeLayerSinkParameter) extends HWBundle(parameter):
    val value = Flipped(UInt(parameter.width))

private class ProbeLayerSinkProbe(parameter: ProbeLayerSinkParameter)
    extends DVBundle[ProbeLayerSinkParameter, ProbeLayerSinkLayers](parameter)

private case class ProbeLayerSinkVerilogParameter() extends VerilogParameter

@generator
private object ProbeLayerSink
    extends VerilogWrapper[ProbeLayerSinkParameter, ProbeLayerSinkLayers, ProbeLayerSinkIO, ProbeLayerSinkProbe,
      ProbeLayerSinkVerilogParameter
    ]:

    override def moduleName(parameter: ProbeLayerSinkParameter): String = "ProbeLayerSink"

    override def verilogModuleName(parameter: ProbeLayerSinkParameter): String = "ProbeLayerSink"

    def verilogParameter(parameter: ProbeLayerSinkParameter): ProbeLayerSinkVerilogParameter =
        ProbeLayerSinkVerilogParameter()

private case class ProbeLayerTopParameter(width: Int) extends Parameter

private given upickle.default.ReadWriter[ProbeLayerTopParameter] = upickle.default.macroRW

private class ProbeLayerTopLayers(parameter: ProbeLayerTopParameter) extends LayerInterface(parameter):
    def layers = DebugLayer.layers

private class ProbeLayerTopIO(parameter: ProbeLayerTopParameter) extends HWBundle(parameter):
    val clock = Flipped(Clock())
    val resetN = Flipped(Reset())

private class ProbeLayerTopProbe(parameter: ProbeLayerTopParameter)
    extends DVBundle[ProbeLayerTopParameter, ProbeLayerTopLayers](parameter)

@generator
private object ProbeLayerTop
    extends Generator[ProbeLayerTopParameter, ProbeLayerTopLayers, ProbeLayerTopIO, ProbeLayerTopProbe]:
    override def moduleName(parameter: ProbeLayerTopParameter): String = "ProbeLayerTop"

    def architecture(parameter: ProbeLayerTopParameter) =
        val io = summon[Interface[ProbeLayerTopIO]]

        val mid = ProbeLayerMid.instantiate(ProbeLayerMidParameter(parameter.width))
        mid.io.clock := io.clock
        mid.io.resetN := io.resetN

        layer("debug"):
            val watched = Wire(UInt(parameter.width))
            watched <== mid.probe.count
            val sink = ProbeLayerSink.instantiate(ProbeLayerSinkParameter(parameter.width))
            sink.io.value := watched

/** One elaboration, lowered both ways: the single text the shape checks read, and the split files the file-list check
  * needs. A generator dumps its module once per process, so both have to come from the same elaboration.
  */
object ProbeLayerTest extends TestSuite:

    private val parameter = ProbeLayerTopParameter(8)

    private lazy val (sv, split) =
        val split = os.temp.dir(prefix = "nzea-probe-split")
        val sv = Elaborate.withLinked(
            ProbeLayerTop.moduleName(parameter),
            os.temp.dir(prefix = "nzea-probe-scratch"),
            cfg => ProbeLayerTop.main(Array("config", cfg.toString, "--width", "8")),
            cfg => ProbeLayerTop.main(Array("design", cfg.toString))
        ): circuit =>
            Elaborate.verilogInto(circuit, split, TestFlow.loweringOptions :+ "--split-verilog")
            Elaborate.verilog(circuit, TestFlow.loweringOptions)
        (sv, split)

    val tests = Tests:

        test("the consumer is instantiated in a bound layer module, beside the signals it watches"):
            assert(sv.contains("bind ProbeLayerTop ProbeLayerTop_debug debug ();"))
            // The deep value reaches the bound module as a hierarchical name across the two levels.
            assert(sv.contains("ProbeLayerSink sink (")) 
            assert(sv.contains(".value (ProbeLayerTop.mid.debug.relay)"))
            // Every level that sends or carries a probe gets its own bind; the reference chain is what crosses.
            assert(sv.contains("bind ProbeLayerSensor ProbeLayerSensor_debug debug ();"))
            assert(sv.contains("bind ProbeLayerMid ProbeLayerMid_debug debug ();"))

        test("the split lowering lists the layer fragment, because the binds are the design's"):
            // firtool does not list the fragments; the dump adds the roots, so a project compiling `filelist.f` gets the
            // binds — and the children the root includes, without compiling them twice.
            assert(
                os.read(split / "filelist.f").linesIterator.filter(_.trim.nonEmpty).toSet == Set(
                    "ProbeLayerTop.sv",
                    "ProbeLayerTop_debug.sv",
                    "ProbeLayerMid.sv",
                    "ProbeLayerMid_debug.sv",
                    "ProbeLayerSensor.sv",
                    "ProbeLayerSensor_debug.sv",
                    "layers-ProbeLayerTop-debug.sv"
                )
            )
            assert(
                os.read(split / "layers-ProbeLayerTop-debug.sv")
                    .contains("bind ProbeLayerTop ProbeLayerTop_debug debug ();")
            )
