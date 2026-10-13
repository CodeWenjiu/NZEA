package nzea_fpga

import utest.*

import nzea_device.devices.{Blink, BlinkParameter}
import nzea_fpga.ip.{ClockPlan, ClockPrimitive, ClockingWizard, ClockingWizardParameter, Ila}
import nzea_rtl.TestFlow

/** What the board top's shape is: its pins (the XDC's names), the modules and IP it instantiates, the tcl the IPs are
  * created from, and the artifacts the ILA's probe table produces. The pins, the tcl and the artifacts are contracts
  * with things outside the RTL — the constraints, the Vivado project, the implementation run, the capture flow — so
  * they are pinned here rather than left to a build to notice.
  */
object LxbArtix7ElaborateTest extends TestSuite:

    /** The board as the bring-up build is made. */
    private val parameter = TopParameter()

    private lazy val sv = TestFlow.lower(
        Top.moduleName(parameter),
        cfg => Top.main(Array("config", cfg.toString)),
        cfg => Top.main(Array("design", cfg.toString))
    )

    /** The board's clock plan, as [[Top]] states it: frequencies only — which primitive realizes them is the plan's own
      * fact. 50 → 100/200 is an exact integer ratio, so the PLL takes it.
      */
    private val clockPlan = ClockPlan(50.0, Seq(200.0, 100.0))

    /** The probes the board watches, as its `mark` and `watch` calls state them: each name a waveform shows and its
      * width. The table itself is built during the elaboration ([[Ila.table]]), and the snapshots below are taken in
      * the dump's own window, so every test reads this dump's table.
      */
    private val watched = Seq(
        "reset" -> 1,
        "locked" -> 1,
        "led1" -> 1,
        "led2" -> 1,
        "blink50_count" -> 26,
        "blink100_count" -> 26
    )

    /** What the debug script resolves each probe by: a marked signal by leaf name through the hierarchy, a bound one at
      * the instance's own level by path — the binding knows the instance, the shared module body does not.
      */
    private val patterns = Seq(
        "*probe_reset*",
        "*probe_locked*",
        "*probe_led1*",
        "*probe_led2*",
        "*blink50/probe_count*",
        "*blink100/probe_count*"
    )

    /** One blinker of its own, at a width the top's build did not use — a generator dumps its module once per parameter
      * per JVM, so the same parameter would be served from that dump. Lowered with no consumer: a declaration is the
      * module's own statement, and a level that watches nothing simply carries the wires.
      */
    private lazy val blinkSv = TestFlow.lower(
        Blink.moduleName(BlinkParameter(27, 1.0)),
        cfg => Blink.main(Array("config", cfg.toString, "--width", "27", "--duty", "1")),
        cfg => Blink.main(Array("design", cfg.toString))
    )

    /** The dump's ILA and its views, snapped in the dump's own window: a later elaboration in this JVM (the standalone
      * blinker below) resets the registry the probes were collected in, so everything derived from the collection is
      * read here, before that elaboration runs. The artifacts are written the same way — the emit and the reads of its
      * files happen now, not when the test below runs.
      */
    private val ila = { val _ = sv; Ila.table }
    private val probeNames = ila.names
    private val probeWidths = ila.widths
    private val probePatterns = ila.patterns
    private val debugTcl = ila.debugTcl

    private val artifacts =
        val dir = os.temp.dir(prefix = "nzea-ila")
        try
            ila.emit(dir)
            (os.read(dir / "ila_debug.tcl"), os.read(dir / "ila_probes.map"), os.read(dir / "ila_capture.tcl"))
        finally os.remove.all(dir)

    /** The IP's tcl and the lists it appears in, from the same dump's window: `ipfiles.f` for the project generator,
      * `simfiles.f` (paths under the generated-IP root) for the xsim flow.
      */
    private val ipArtifacts =
        val dir = os.temp.dir(prefix = "nzea-ip")
        try
            ClockingWizard.emit(dir)
            (os.read(dir / "ip_clk_wiz_0.tcl"), os.read(dir / "ipfiles.f"), os.read(dir / "simfiles.f"))
        finally os.remove.all(dir)

    val tests = Tests:

        test("the top's ports are the board's pins, and nothing else"):
            assert(
                TestFlow.ports(sv, "Top") == Map(
                    "CLK_50M" -> 1,
                    "RESET" -> 1,
                    "LED1" -> 1,
                    "LED2" -> 1
                )
            )

        test("the design is the reset synchronizer, the two blinkers and the clocking wizard — and no ILA"):
            assert(sv.contains("module Top("))
            assert(sv.contains("ResetSync sync ("))
            assert(sv.contains("Blink blink50 (") && sv.contains("Blink blink100 ("))
            // The IP keeps its instance name (it names nets and shows up in waveforms).
            assert(sv.contains("clk_wiz_0 clockingWizard ("))
            // The ILA is a debug core the netlist debug flow builds, not an RTL instance: nothing of it is here.
            assert(!sv.contains("u_ila_0"))

        test("the oscillator feeds the wizard directly, and the design's own logic off a global buffer"):
            // The clock generator's input is the pin as it is — its compensation is about the IO buffer's path — and
            // the blinkers run off a global buffer of the pin, so the tool has no reason to put its own buffer on
            // the generator's input (a PLL fed through one fails the IO-clock DRC: REQP-1712).
            assert(sv.contains("Bufg clk50Buffer ("))
            assert(sv.contains(".I (CLK_50M)"))
            assert(sv.contains(".clk_in1  (CLK_50M)"))
            assert(sv.contains(".clock  (_clk50Buffer_O)"))
            // The 100 MHz blinker waits for the wizard's `locked`.
            assert(sv.contains("clk_out2"))
            // And it is held in reset while the button is down or the wizard has not locked: released only with
            // both (`syncN` is the raw active-low level — one is released).
            assert(sv.contains(".resetN (_sync_syncN & _clockingWizard_locked)"))

        // Vivado synthesis silently drops SystemVerilog `bind`, so a layer-hosted observer vanishes from the netlist
        // without a warning (see `nzea_rtl.probe.ProbeLayerTest` for the mechanism and the experiment). What watches a
        // board signal instead is `mark_debug`: the ILA is a debug core the implementation run creates on the
        // synthesized netlist, and this guards against either half drifting back.
        test("the board's ILA is not in the RTL: the netlist debug flow builds it"):
            assert(!sv.contains("bind "))
            assert(!sv.contains("u_ila_0"))
            assert(!sv.contains("Top_debug"))

        test("the top marks its own signals for the netlist debug flow, and keeps its clock"):
            // One `mark_debug` per marked probe (the top's four) plus the blinker's own declaration, which the
            // single-file lowering carries in `Blink`'s body.
            assert(sv.contains("module Blink("))
            assert(sv.split("mark_debug").length - 1 == 5)
            // The core's clock is the fabric's — the global buffer's output, not the pin.
            assert(sv.contains("assign ila_clock = _clk50Buffer_O"))

        test("a declaration is the module's own: the wire and the attribute are in its Verilog, ports unchanged"):
            assert(blinkSv.contains("(* mark_debug = \"true\" *)"))
            assert(blinkSv.contains("probe_count"))
            assert(TestFlow.ports(blinkSv, "Blink") == Map("clock" -> 1, "resetN" -> 1, "led" -> 1, "count" -> 27))

        test("the probe table is the wiring's: what is watched, as what, and how it resolves"):
            assert(probeNames == watched.map(_._1))
            assert(probeWidths == watched.map(_._2))
            assert(probePatterns == patterns)

        test("a binding names a declaration that exists"):
            // The bound probes are in the table, so `watch` found the blinkers' declarations; a binding of a name no
            // module declared fails the elaboration instead of reaching a build.
            assert(probePatterns.contains("*blink50/probe_count*"))
            assert(probePatterns.contains("*blink100/probe_count*"))

        test("the clocking wizard's tcl follows the plan"):
            val tcl = ClockingWizard.tcl(ClockingWizardParameter("clk_wiz_0", clockPlan))
            assert(tcl.contains("module_name clk_wiz_0"))
            // The primitive is the plan's: the PLL for this plan (the smaller block), not the superset.
            assert(tcl.contains("CONFIG.PRIMITIVE {PLL}"))
            assert(tcl.contains("CONFIG.PRIM_IN_FREQ {50.000}"))
            assert(tcl.contains("CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {100.000}"))

        test("the primitive is what the frequencies need, not a choice"):
            // Exact integer ratios are the PLL's bread and butter.
            assert(ClockPlan(50.0, Seq(200.0, 100.0)).primitive == ClockPrimitive.Pll)
            // A fractional ratio is not what takes the PLL away: 148.5 = 27 × 33 / 6 is an integer solution (VCO 891).
            assert(ClockPlan(27.0, Seq(148.5)).primitive == ClockPrimitive.Pll)
            // And an integer-looking plan is not what keeps it: 100 → 33.3 has no exact PLL solution.
            assert(ClockPlan(100.0, Seq(33.3)).primitive == ClockPrimitive.Mmcm)
            // The MMCM's extra outputs are its own: five outputs is more than a PLL has.
            assert(ClockPlan(50.0, Seq.fill(5)(100.0)).primitive == ClockPrimitive.Mmcm)
            // The wizard's IP is the same either way; only its configuration states which block.
            assert(
                ClockingWizard
                    .tcl(ClockingWizardParameter("clk_wiz_0", ClockPlan(100.0, Seq(33.3))))
                    .contains("CONFIG.PRIMITIVE {MMCM}")
            )

        test("the clock plan's flag spelling round-trips"):
            assert(ClockPlan.parse("50:200,100") == Right(clockPlan))
            assert(ClockPlan.parse("27:148.5") == Right(ClockPlan(27.0, Seq(148.5))))
            // The spelling is the frequencies' alone: a primitive named here is not part of a plan.
            assert(ClockPlan.parse("50:200,100:MMCM").isLeft)
            assert(ClockPlan.parse("50:").isLeft)
            assert(ClockPlan.parse("x:200").isLeft)
            // Frequencies are on the kHz grid — the IP writes three decimals, and the plan is compared exactly.
            assert(intercept[IllegalArgumentException](ClockPlan(50.0, Seq(33.3333))).getMessage.contains("kHz grid"))

        test("the ILA's debug script follows the probe table"):
            val tcl = debugTcl
            assert(tcl.contains("create_debug_core u_ila_0 ila"))
            assert(tcl.contains(s"set_property C_DATA_DEPTH ${ila.depth} [get_debug_cores u_ila_0]"))
            // probe0 comes with the core; every further probe is one more port, at the width declared.
            assert(tcl.split("create_debug_port u_ila_0 probe").length - 1 == watched.size - 1)
            assert(tcl.contains("connect_debug_port u_ila_0/clk [ila_leaf {*ila_clock*} 1]"))
            val resolves = Seq.fill(4)("ila_leaf") ++ Seq.fill(2)("ila_path")
            for i <- watched.indices do
                assert(tcl.contains(s"set_property port_width ${watched(i)._2} [get_debug_ports u_ila_0/probe$i]"))
                assert(
                    tcl.contains(
                        s"connect_debug_port u_ila_0/probe$i [${resolves(i)} {${patterns(i)}} ${watched(i)._2}]"
                    )
                )

        test("the ILA's artifacts follow the probe table"):
            val (debug, map, capture) = artifacts
            // The debug script is the table's own text: what the implementation run sources is what was stated.
            assert(debug == debugTcl)
            // One net key and one probe-port key per probe: the net name when the export keeps it, the port name when
            // it does not.
            val netKeys = Seq(
                "probe_reset" -> "reset",
                "probe_locked" -> "locked",
                "probe_led1" -> "led1",
                "probe_led2" -> "led2",
                "blink50/probe_count" -> "blink50_count",
                "blink100/probe_count" -> "blink100_count"
            )
            for (key, name) <- netKeys do assert(map.contains(s"$key $name"))
            for (name, i) <- watched.map(_._1).zipWithIndex do assert(map.contains(s"probe$i $name"))
            // The capture script bakes the depth's trigger position in, so it tracks the map.
            assert(capture.contains(s"CONTROL.TRIGGER_POSITION ${ila.depth / 4}"))
            // The trigger form: empty (or `snapshot`) triggers immediately; `<probe>[=<value>]` arms on one probe's
            // compare value, the probe resolved to the manager's name through the map beside the script.
            assert(capture.contains("run_hw_ila -trigger_now $hw_ila"))
            assert(capture.contains("set_property TRIGGER_COMPARE_VALUE $literal $probe"))
            assert(capture.contains("ila_probes.map"))

        test("the clocking wizard declares its sim sources beside its tcl"):
            val (tcl, ips, sims) = ipArtifacts
            assert(tcl.contains("create_ip -name clk_wiz"))
            assert(ips == "ip_clk_wiz_0.tcl\n")
            // The entries the xsim flow resolves under its own generated copy of the IP: one directory per module name.
            assert(sims == "clk_wiz_0/clk_wiz_0_sim_netlist.v\n")
