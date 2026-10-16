package nzea_rtl.stdlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.TestFlow
import utest.*

/** The design the tests lower, and the shape of the failure: `kind` picks one per elaboration (0 builds the network).
  */
private case class ResetsTestParameter(kind: Int) extends Parameter

given upickle.default.ReadWriter[ResetsTestParameter] = upickle.default.macroRW

private class ResetsTestLayers(parameter: ResetsTestParameter) extends LayerInterface(parameter):
    def layers = Seq.empty

private class ResetsTestIO(parameter: ResetsTestParameter) extends HWBundle(parameter):
    val clockA = Flipped(Clock())
    val clockB = Flipped(Clock())
    val pwrGood = Flipped(Bool())
    val bReady = Flipped(Bool())

private class ResetsTestProbe(parameter: ResetsTestParameter)
    extends DVBundle[ResetsTestParameter, ResetsTestLayers](parameter)

@generator
private object ResetsTestDesign extends Generator[ResetsTestParameter, ResetsTestLayers, ResetsTestIO, ResetsTestProbe]:
    override def moduleName(parameter: ResetsTestParameter): String = s"ResetsTestDesign_${parameter.kind}"

    def architecture(parameter: ResetsTestParameter) =
        val io = summon[Interface[ResetsTestIO]]
        Resets.term("pwrGood")(io.pwrGood)
        Resets.term("bReady")(io.bReady)
        parameter.kind match
            // A domain that waits for a reason nobody declared: its name is a typo or a rename, and it fails here.
            case 1 =>
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood", "nothingDeclaredThis"))
                Resets.materialize()
            // Two domains with one name: one probe name, one synchronizer name, two statements.
            case 2 =>
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood"))
                val _ = Resets.domain("a", io.clockB, Seq("bReady"))
                Resets.materialize()
            // Two reasons with one name: a term is one reason.
            case 3 =>
                Resets.term("pwrGood")(io.pwrGood)
                Resets.materialize()
            // Two names the leaf search cannot tell apart: `probe_pwr` matches `probe_pwrGood` too.
            case 4 =>
                Resets.term("pwr")(io.pwrGood)
                Resets.materialize()
            // A domain stated after the network was wired: its synchronizer would never be driven.
            case 5 =>
                Resets.materialize()
                val _ = Resets.domain("late", io.clockA, Seq("pwrGood"))
            // A domain that waits for no reason: it would be a synchronizer with a constant on its input.
            case 6 =>
                val _ = Resets.domain("a", io.clockA, Seq.empty)
                Resets.materialize()
            // The same request twice: one name is one reset, so the second is the first's net (one synchronizer, one
            // release edge), not a second chain that could resolve the same asynchronous level differently.
            case 7 =>
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood"))
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood"))
                Resets.materialize()
            // The same name with a different recipe: a contradiction the name cannot carry.
            case 8 =>
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood"))
                val _ = Resets.domain("a", io.clockA, Seq("bReady"))
                Resets.materialize()
            case _ =>
                val _ = Resets.domain("a", io.clockA, Seq("pwrGood"))
                val _ = Resets.domain("b", io.clockB, Seq("pwrGood", "bReady"))
                Resets.materialize()

/** What the reset network promises, and what it refuses: one synchronizer per domain on that domain's own clock, the
  * conjunction of the reasons the domain named, every reason and every domain watchable — and the failure modes that
  * would otherwise reach a build (a name nobody declared, a doubled name, a name the leaf search cannot tell apart).
  */
object ResetsTest extends TestSuite:

    private def lower(kind: Int): String =
        TestFlow.lower(
            ResetsTestDesign.moduleName(ResetsTestParameter(kind)),
            cfg => ResetsTestDesign.main(Array("config", cfg.toString, "--kind", kind.toString)),
            cfg => ResetsTestDesign.main(Array("design", cfg.toString))
        )

    private def refusal(kind: Int): String =
        intercept[IllegalArgumentException](lower(kind)).getMessage

    val tests = Tests:

        test("a domain is one synchronizer, on its own clock, over its own conjunction"):
            val sv = lower(0)
            println(s"--- resets probe ---\n$sv\n--- probe end ---")
            // One synchronizer per domain, named after the domain it serves.
            assert(sv.contains("ResetSync aSync ("))
            assert(sv.contains("ResetSync bSync ("))
            // The one-reason domain takes the reason itself; the two-reason one takes the conjunction — inline when the
            // tools can prove it is used once (a shared prefix is what makes them keep a net, named for what it joins:
            // the board's own dump shows `pwrGoodAndBReady`-shaped nets).
            assert(sv.matches("(?s).*ResetSync aSync \\(.*\\.clock \\(clockA\\).*\\.rawN  \\(pwrGood\\).*"))
            assert(sv.matches("(?s).*ResetSync bSync \\(.*\\.clock \\(clockB\\).*\\.rawN  \\(pwrGood & bReady\\).*"))
            // Every reason and every domain is watchable: which reason is holding is the first question a reset
            // network raises, and the probe mechanism is what answers it from a capture.
            for probe <- Seq("pwrGood", "bReady", "aReset", "bReset") do assert(sv.contains(s"probe_$probe"))

        test("a reason nobody declared fails the wiring, and says which"):
            val message = refusal(1)
            assert(message.contains("waits for a reason nobody declared: 'nothingDeclaredThis'"))
            assert(message.contains("declared: pwrGood, bReady"))

        test("one name with two recipes is refused"):
            // Two domains asked for under one name with different reasons: the same shape as kind 8, reached from the
            // other direction (a second clock here, different reasons there).
            assert(refusal(2).contains("'a' is asked for twice with different reasons"))

        test("two reasons with one name are refused"):
            assert(refusal(3).contains("two reset terms named 'pwrGood'"))

        test("a reason a domain waits for must be tellable apart from every other"):
            val message = refusal(4)
            assert(message.contains("'pwr' and 'pwrGood' cannot both name a declared net"))

        test("a domain stated after the network was wired is refused"):
            assert(refusal(5).contains("the reset network is wired once"))

        test("a domain that waits for nothing is refused"):
            assert(refusal(6).contains("waits for nothing"))

        test("one name is one reset: the same request shares it, a different one is a contradiction"):
            // Same name, same reasons: one synchronizer in the netlist, however many consumers asked — one release edge.
            // Counted by line, not by `split`: the instance's text carries a parenthesis.
            val sv = lower(7)
            assert(sv.linesIterator.count(_.contains("ResetSync aSync (")) == 1)
            // Same name, different reasons: the name cannot mean two things.
            assert(refusal(8).contains("'a' is asked for twice with different reasons"))
