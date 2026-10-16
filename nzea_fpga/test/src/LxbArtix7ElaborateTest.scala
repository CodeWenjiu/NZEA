package nzea_fpga

import utest.*

import nzea_device.devices.{Blink, BlinkParameter}
import nzea_fpga.device.ddr3.{Ddr3Clocks, Ddr3Pads}
import nzea_fpga.ip.{ClockOut, ClockPlan, ClockPrimitive, ClockingWizard, ClockingWizardParameter, Ila}
import nzea_rtl.{DumpRegistry, TestFlow}

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

    /** The clock plan the design asks for, as its own requests state it ([[nzea_fpga.Clocks]]): the order is the
      * askers' — the bus's grids first, because the device states them where its instances are named ([[Ddr3Side]]) and
      * it is built before the level's own blinker asks for its 100 MHz.
      *
      * The 200 MHz the delay taps are calibrated against, the same clock held at the strobe grid's phase (the bus's own
      * domain, and the serializer's `CLKDIV`'s clock, so a load edge lands on its shift edges), the memory PHY's two
      * 400 MHz launch grids (the second a quarter period later, which is the strobe's own grid), what the deserializers
      * clock their output register with (that 200 MHz's 180° twin, half a period off the fabric's read), and the
      * level's 100 MHz. A phase takes the plan away from the PLL (which has none to state), so the wizard the design
      * builds is the MMCM.
      *
      * The order is the plan's own — `clk_out1` first — and it is pinned here because two constraints name grids by the
      * slot the plan gives them, and the IP this design builds is the one these six keys in this order describe.
      */
    private val clockPlan = ClockPlan(
        50.0,
        Seq(
            ClockOut(200.0, 0.0),
            ClockOut(200.0, 45.0),
            ClockOut(400.0, 0.0),
            ClockOut(400.0, 90.0),
            ClockOut(200.0, 180.0),
            ClockOut(100.0, 0.0)
        )
    )

    /** The probes the board watches, as its `mark` calls state them: each name a waveform shows and its width, in the
      * order the statements were made — the reset network's reasons and domains first (they are stated as the design is
      * wired), then the bus's, then the campaign's (the level wires it right after the bus), then the level's own clock
      * domain and the panel, then the top's own two. Every one of them is *marked* (declare + bind in one step): the
      * modules that carry registers are instantiated once, so one name is one net. The table itself is built during the
      * elaboration ([[Ila.table]]), and the snapshots below are taken in the dump's own window, so every test reads
      * this dump's table.
      */
    private val watched = Seq(
        // The reset network: every reason as it is produced, and every domain as its registers see it (1 = in reset).
        "buttonPin" -> 1,
        "board50Reset" -> 1,
        "ddr3TapsReady" -> 1,
        "ddr3Reset" -> 1,
        // The bus, whose instance is built next — its own statements: the round, and the two facts the rounds are
        // measured by (the alignment the capture found, and where the tap sweep stands).
        "state" -> 5,
        "initDone" -> 1,
        "alignedAt" -> 6,
        "tap" -> 5,
        "calibPlane" -> 2,
        "calibrating" -> 1,
        "lockedPlane" -> 2,
        "lockedTap" -> 5,
        "recent" -> 96,
        "captured" -> 704,
        "checked" -> 704,
        "failTaps" -> 96,
        "roundIn" -> 7,
        "timer" -> 24,
        "dqIn" -> 64,
        // The campaign ([[nzea_fpga.logic.Ddr3Soak]]): what the rounds account to — the rate, and per (pattern,
        // address) pair what the board has cleared and what it has seen fail.
        "rounds" -> 32,
        "misses" -> 32,
        "blocks" -> 32,
        "combo" -> 6,
        "missPat" -> 3,
        "missAddr" -> 3,
        "missTap" -> 5,
        "passMask" -> 64,
        "missMask" -> 64,
        // The generator's lock (the level's second reason, declared once the wizard exists) and the level's own domain.
        "pllLocked" -> 1,
        "board100Reset" -> 1,
        // The bring-up panel (its declarations are bound per instance — the one shared body here), and the top's own two.
        "blink50_count" -> 26,
        "blink100_count" -> 26,
        "led1" -> 1,
        "led2" -> 1
    )

    /** What the debug script resolves each probe by: a marked signal by leaf name (all but the panel's counters, which
      * are *bound* at their instances and so carry a path) — the pattern says which: a binding's is the instance's path
      * (`*<instance>/<net>*`).
      */
    private val patterns = Seq(
        "*probe_buttonPin*",
        "*probe_board50Reset*",
        "*probe_ddr3TapsReady*",
        "*probe_ddr3Reset*",
        "*probe_state*",
        "*probe_initDone*",
        "*probe_alignedAt*",
        "*probe_tap*",
        "*probe_calibPlane*",
        "*probe_calibrating*",
        "*probe_lockedPlane*",
        "*probe_lockedTap*",
        "*probe_recent*",
        "*probe_captured*",
        "*probe_checked*",
        "*probe_failTaps*",
        "*probe_roundIn*",
        "*probe_timer*",
        "*probe_dqIn*",
        "*probe_rounds*",
        "*probe_misses*",
        "*probe_blocks*",
        "*probe_combo*",
        "*probe_missPat*",
        "*probe_missAddr*",
        "*probe_missTap*",
        "*probe_passMask*",
        "*probe_missMask*",
        "*probe_pllLocked*",
        "*probe_board100Reset*",
        "*blink50/probe_count*",
        "*blink100/probe_count*",
        "*probe_led1*",
        "*probe_led2*"
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

    /** The constraints the dump wrote, as files: one per part of the design that declares pins, from the same dump's
      * window. They ride the dump's own emitter list ([[DumpRegistry.emitAll]]), which is where a project generator
      * reads them from — the list beside them is its index.
      */
    private val xdcArtifacts =
        val _ = sv
        val dir = os.temp.dir(prefix = "nzea-xdc")
        try
            DumpRegistry.emitAll(dir)
            (os.read(dir / "board.xdc"), os.read(dir / "ddr3.xdc"), os.read(dir / "xcdfiles.f"))
        finally os.remove.all(dir)

    /** The dump's own modules, from the same window as the constraints: the pads' bodies are written beside the RTL and
      * listed in `filelist.f`, so a dump that instantiates them carries them (`Ddr3Pads`).
      */
    private val padArtifacts =
        val _ = sv
        val dir = os.temp.dir(prefix = "nzea-pads")
        try
            Ddr3Pads.emitModules(dir)
            (
                os.read(dir / "Ddr3DqPads.sv"),
                os.read(dir / "Ddr3DqsPads.sv"),
                os.read(dir / "Ddr3SdrPads15.sv"),
                os.read(dir / "Ddr3LevelPads.sv"),
                os.read(dir / "Ddr3CkPads.sv"),
                os.read(dir / "Ddr3IdelayCtrl.sv"),
                os.read(dir / "filelist.f").linesIterator.toSeq
            )
        finally os.remove.all(dir)

    /** Every pin the top declares, from its own module header — the set the constraints have to bind, all of it. */
    private val topPorts: Set[String] = TestFlow.ports(sv, "Top").keySet

    /** The clock plan the design asked for, and the names the constraints spell two of its grids by — read in the
      * dump's own window, like the probe table: a later elaboration in this JVM resets the registry the requests were
      * collected in ([[Clocks]]), so these are snapshotted here rather than asked for from a test body.
      */
    private val plannedClocks = { val _ = sv; Clocks.planned }

    /** What a constraint file binds, from its `get_ports` targets: brace-grouped or bare, and a vector's index dropped
      * to the port the index is of.
      */
    private def constrained(xdc: String): Set[String] =
        val getPorts = """get_ports\s+\{([^}]*)\}|get_ports\s+(\w+)""".r
        getPorts
            .findAllMatchIn(xdc)
            .flatMap(entry => Option(entry.group(1)).getOrElse(entry.group(2)).split("\\s+").toSeq)
            .map(_.replaceAll("\\[[^\\]]*\\]$", ""))
            .toSet

    val tests = Tests:

        test("the top's ports are the board's pins, and nothing else"):
            // The board's pins are this module's own ports — the design *is* the top ([[nzea_fpga.Top]]): the oscillator,
            // the button, the LEDs, and the DDR3 bus's pads, the `inout` data and strobes included. Nothing is left over
            // for a second top above it to declare.
            assert(
                TestFlow.ports(sv, "Top") == Map(
                    "CLK_50M" -> 1,
                    "RESET" -> 1,
                    "LED1" -> 1,
                    "LED2" -> 1,
                    "DDR3_CK_P" -> 1,
                    "DDR3_CK_N" -> 1,
                    "DDR3_ADDR" -> 15,
                    "DDR3_BA" -> 3,
                    "DDR3_DM" -> 2,
                    "DDR3_RAS_N" -> 1,
                    "DDR3_CAS_N" -> 1,
                    "DDR3_WE_N" -> 1,
                    "DDR3_CKE" -> 1,
                    "DDR3_ODT" -> 1,
                    "DDR3_RESET_N" -> 1,
                    "DDR3_DQ" -> 16,
                    "DDR3_DQS_P" -> 2,
                    "DDR3_DQS_N" -> 2
                )
            )

        test("the top is the design, and the pads are its own modules — an ILA is not"):
            assert(sv.contains("module Top("))
            // The reset network: one synchronizer per domain, each named after the domain it serves ([[nzea_rtl.stdlib.Resets]]).
            assert(sv.contains("ResetSync board50Sync ("))
            assert(sv.contains("ResetSync board100Sync ("))
            assert(sv.contains("ResetSync ddr3Sync ("))
            assert(sv.contains("Blink blink50 (") && sv.contains("Blink blink100 ("))
            // The IP keeps its instance name (it names nets and shows up in waveforms).
            assert(sv.contains("clk_wiz_0 clockingWizard ("))
            // The controller is an instance of the mmcm-made design, held in reset until the pads' taps are calibrated.
            assert(sv.contains("Ddr3 ddr3 ("))
            // The bus's campaign side, wired to this level's campaign ([[nzea_fpga.logic.Ddr3Soak]]) where both instances
            // are named: the beats and the address out, the round's answer back — and the domain the verdict is stamped
            // in, which is the controller's own (the same clock net and the same domain reset).
            assert(sv.contains("Ddr3Soak soak ("))
            assert(
                sv.matches(
                    "(?s).*Ddr3Soak soak \\(.*\\.clock\\s+\\(_clockingWizard_clk_out2\\).*\\.resetN\\s+\\(_ddr3Sync_syncN\\).*" +
                        "\\.campBeats\\s+\\(_soak_campBeats\\).*\\.campPass\\s+\\(_ddr3_campPass\\).*"
                )
            )
            // The pads: modules of the circuit, instantiated by the top, with the analog nets attached to its own ports.
            // (The ports' own padding is the lowering's; what is checked is the connection.)
            assert(sv.contains("Ddr3DqPads dqPads ("))
            assert(sv.matches("(?s).*\\.DQ\\s+\\(DDR3_DQ\\).*"))
            assert(sv.contains("Ddr3DqsPads dqsPads ("))
            assert(sv.matches("(?s).*\\.DQS_P\\s+\\(DDR3_DQS_P\\).*"))
            assert(sv.matches("(?s).*\\.DQS_N\\s+\\(DDR3_DQS_N\\).*"))
            assert(sv.contains("Ddr3CkPads ckPads ("))
            assert(sv.contains("Ddr3SdrPads15 addrPads ("))
            assert(sv.contains("Ddr3SdrPads1 rasPads ("))
            assert(sv.contains("Ddr3LevelPads ckePads ("))
            assert(sv.contains("Ddr3IdelayCtrl delayCtrl ("))
            // The calibrator's ready is what holds the controller in reset, and the pads' registers run on the
            // controller's own domain reset.
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _delayCtrl_READY)"))
            assert(sv.contains(".ringReset (~_ddr3Sync_syncN)"))
            // The ILA is a debug core the netlist debug flow builds, not an RTL instance: nothing of it is here.
            assert(!sv.contains("u_ila_0"))

        test("the oscillator feeds the wizard directly, and the design's own logic off a global buffer"):
            // The clock generator's input is the pin as it is — its compensation is about the IO buffer's path — and
            // the level's own logic (the reset synchronizer, the LED panel) runs off a global buffer of the pin, so the
            // tool has no reason to put its own buffer on the generator's input (a PLL fed through one fails the
            // IO-clock DRC: REQP-1712).
            assert(sv.contains("Bufg clk50Buffer ("))
            assert(sv.contains(".I (CLK_50M)"))
            assert(sv.contains(".clk_in1  (CLK_50M)"))
            assert(sv.contains(".clock (_clk50Buffer_O)"))
            assert(sv.matches("(?s).*\\.clock50\\s+\\(_clk50Buffer_O\\).*"))
            // The 100 MHz blinker's clock is the level's own grid.
            assert(sv.matches("(?s).*\\.clock100\\s+\\(_clockingWizard_clk_out6\\).*"))
            // Its reset is the domain's own, already synchronized there — the button's pin and the generator's lock
            // reach it as *reasons* through that domain's synchronizer, not as a combination wired here.
            assert(sv.matches("(?s).*\\.resetN100\\s+\\(_board100Sync_syncN\\).*"))
            assert(sv.matches("(?s).*\\.resetN50\\s+\\(_board50Sync_syncN\\).*"))
            // The DDR3 domain's reset is its own: the reasons it waits for are composed once ([[nzea_rtl.stdlib.Resets]]),
            // and what the controller sees is that domain's own synchronizer's release (an asynchronous feed from two
            // other domains was the board's worst path, −7 ns at 400 MHz). The pads' tap calibration is one of the
            // reasons: nothing about the device is touched before the taps are usable.
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _delayCtrl_READY)"))
            // The port list's names are column-aligned, so this one's padding grows with the longest name beside it
            // (the write delay's tap is the newest); the connection is what the test is about, not the whitespace.
            assert(sv.matches("(?s).*\\.resetN\\s+\\(_ddr3Sync_syncN\\).*"))

        // The reset network's own statement: a domain's reset is the conjunction of the reasons it waits for, taken
        // through one synchronizer clocked by that domain ([[nzea_rtl.stdlib.Resets]]). It is pinned here because it is
        // what makes a new reason one line at its producer and a new domain one list at its consumer.
        test("the reset network is each domain's own conjunction, on its own clock"):
            // The oscillator's domain: the pin as it is, and nothing else.
            assert(sv.matches("(?s).*\\.rawN  \\(RESET\\).*"))
            // The two reasons the generated domains share — the button's *pin* and the wizard's lock: a domain waits for
            // the raw reason, never for a level another domain happened to clean. Composed once and shared (the tools
            // fold identical logic), which is why the net is named for what it joins rather than for the domain that
            // happened to ask first.
            assert(sv.matches("(?s).*wire\\s+buttonPinAndPllLocked = RESET & _clockingWizard_locked;.*"))
            assert(sv.matches("(?s).*ResetSync board100Sync \\(.*\\.rawN  \\(buttonPinAndPllLocked\\).*"))
            // The device adds its own ready to the same pair, and its synchronizer runs on the bus's own grid.
            assert(sv.matches("(?s).*ResetSync ddr3Sync \\(.*\\.clock \\(_clockingWizard_clk_out2\\).*"))
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _delayCtrl_READY)"))
            // Every reason and every domain is watchable: the first question a reset network raises is which reason holds.
            for probe <- Seq(
                    "buttonPin",
                    "pllLocked",
                    "ddr3TapsReady",
                    "board50Reset",
                    "board100Reset",
                    "ddr3Reset"
                )
            do assert(sv.contains(s"probe_$probe"))

        // Vivado synthesis silently drops SystemVerilog `bind`, so a layer-hosted observer vanishes from the netlist
        // without a warning (see `nzea_rtl.probe.ProbeLayerTest` for the mechanism and the experiment). What watches a
        // board signal instead is `mark_debug`: the ILA is a debug core the implementation run creates on the
        // synthesized netlist, and this guards against either half drifting back.
        test("the board's ILA is not in the RTL: the netlist debug flow builds it"):
            assert(!sv.contains("bind "))
            assert(!sv.contains("u_ila_0"))
            assert(!sv.contains("Top_debug"))

        test("the top marks its own signals for the netlist debug flow, and keeps its clock"):
            // One `mark_debug` per marked probe: the reset network's six (three reasons, three domains), the top's own
            // two, the blinker's declaration, the bus's fifteen and the campaign's nine — the single-file lowering
            // carries them all in their modules' bodies.
            assert(sv.contains("module Blink("))
            assert(sv.contains("module Ddr3("))
            assert(sv.split("mark_debug").length - 1 == 33)
            // The core's clock is the oscillator's own, buffered — not one of the wizard's (its logic closes at
            // 50 MHz, and those edges line up with the fabric's instead of sitting a quarter period off them).
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
            // The design's own requests are the plan: six keys, in the order the slots are named from ([[Clocks]]). A
            // wiring statement that moved a grid would fail here instead of silently re-slotting the IP and the
            // constraints that name a grid by the slot the plan gives it.
            assert(plannedClocks == clockPlan.outputs)
            val tcl = ClockingWizard.tcl(ClockingWizardParameter(Clocks.ipName, clockPlan))
            assert(tcl.contains("module_name clk_wiz_0"))
            // The primitive is the plan's: the MMCM, because a phase is part of what the plan states and the PLL has
            // none to state ([[ClockPrimitive.forPlan]]).
            assert(tcl.contains("CONFIG.PRIMITIVE {MMCM}"))
            assert(tcl.contains("CONFIG.PRIM_IN_FREQ {50.000}"))
            assert(tcl.contains("CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT3_REQUESTED_OUT_FREQ {400.000}"))
            assert(tcl.contains("CONFIG.CLKOUT4_REQUESTED_OUT_FREQ {400.000}"))
            // The phase is requested, not dictated: the wizard's achieved fields are its own generated properties
            // (writing one is rejected — see `ClockingWizard.tcl`), so what the plan can do is state the phase and
            // state that it is realizable exactly.
            assert(tcl.contains("CONFIG.CLKOUT4_REQUESTED_PHASE {90.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_PHASE {45.000}"))
            assert(tcl.contains("CONFIG.CLKOUT5_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT5_REQUESTED_PHASE {180.000}"))
            assert(tcl.contains("CONFIG.CLKOUT6_REQUESTED_OUT_FREQ {100.000}"))
            assert(ClockPlan.parse("50:200,200@45,400,400@90,200@180,100").exists(_ == clockPlan))

        test("the primitive is what the frequencies need, not a choice"):
            // Exact integer ratios are the PLL's bread and butter.
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(100.0, 0.0))).primitive == ClockPrimitive.Pll)
            // A fractional ratio is not what takes the PLL away: 148.5 = 27 × 33 / 6 is an integer solution (VCO 891).
            assert(ClockPlan(27.0, Seq(ClockOut(148.5, 0.0))).primitive == ClockPrimitive.Pll)
            // And an integer-looking plan is not what keeps it: 100 → 33.3 has no exact PLL solution.
            assert(ClockPlan(100.0, Seq(ClockOut(33.3, 0.0))).primitive == ClockPrimitive.Mmcm)
            // The MMCM's extra outputs are its own: five outputs is more than a PLL has.
            assert(ClockPlan(50.0, Seq.fill(5)(ClockOut(100.0, 0.0))).primitive == ClockPrimitive.Mmcm)
            // A phase is not the PLL's: a plan that asks for one needs the block that can place it.
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(400.0, 90.0))).primitive == ClockPrimitive.Mmcm)
            // And the phase has to be one the MMCM's fine grid can land on: 45° of a 400 MHz output at the VCO this
            // plan picks (800 MHz) is 22.5 of the grid's 56 steps of a period — no whole step of the fine shift.
            assert(ClockPrimitive.mmcmSolution(ClockPlan(50.0, Seq(ClockOut(400.0, 90.0)))).isDefined)
            // The wizard's IP is the same either way; only its configuration states which block.
            assert(
                ClockingWizard
                    .tcl(ClockingWizardParameter("clk_wiz_0", ClockPlan(100.0, Seq(ClockOut(33.3, 0.0)))))
                    .contains("CONFIG.PRIMITIVE {MMCM}")
            )

        test("the clock plan's flag spelling round-trips"):
            assert(ClockPlan.parse("50:200,200@45,400,400@90,200@180,100") == Right(clockPlan))
            assert(ClockPlan.parse("27:148.5") == Right(ClockPlan(27.0, Seq(ClockOut(148.5, 0.0)))))
            // The spelling is the frequencies' and phases' alone: a primitive named here is not part of a plan.
            assert(ClockPlan.parse("50:200,100:MMCM").isLeft)
            assert(ClockPlan.parse("50:").isLeft)
            assert(ClockPlan.parse("x:200").isLeft)
            // Frequencies are on the kHz grid — the IP writes three decimals, and the plan is compared exactly.
            assert(
                intercept[IllegalArgumentException](ClockPlan(50.0, Seq(ClockOut(33.3333, 0.0)))).getMessage
                    .contains("kHz grid")
            )
            // A phase lives in one period: 360° is 0° again and is spelled as such.
            assert(ClockOut.parse("400@360").isLeft)
            assert(ClockOut.parse("400@90") == Right(ClockOut(400.0, 90.0)))

        test("the ILA's debug script follows the probe table"):
            val tcl = debugTcl
            assert(tcl.contains("create_debug_core u_ila_0 ila"))
            assert(tcl.contains(s"set_property C_DATA_DEPTH ${ila.depth} [get_debug_cores u_ila_0]"))
            // probe0 comes with the core; every further probe is one more port, at the width declared.
            assert(tcl.split("create_debug_port u_ila_0 probe").length - 1 == watched.size - 1)
            assert(tcl.contains("connect_debug_port u_ila_0/clk [ila_leaf {*ila_clock*} 1]"))
            // Both searches exclude the core's own nets: `connect_debug_port` inserts a net named
            // `<core>_<net>` for every probe connected, and a declared name is a prefix of a longer one
            // (`probe_locked` of `probe_lockedPlane`) — so a leaf search that ran after the longer probe was
            // connected found the core's net for it too and failed the build on the count (the probe order
            // decides whether it does, which is not something a script should depend on).
            assert(tcl.contains("-filter \"NAME !~ u_ila_0*\""))
            assert(tcl.contains("-filter \"NAME =~ $pattern && NAME !~ u_ila_0*\""))
            val resolves = patterns.map(pattern => if pattern.contains("/") then "ila_path" else "ila_leaf")
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
                "probe_buttonPin" -> "buttonPin",
                "probe_board50Reset" -> "board50Reset",
                "probe_ddr3TapsReady" -> "ddr3TapsReady",
                "probe_ddr3Reset" -> "ddr3Reset",
                "probe_pllLocked" -> "pllLocked",
                "probe_board100Reset" -> "board100Reset",
                "probe_led1" -> "led1",
                "probe_led2" -> "led2",
                "blink50/probe_count" -> "blink50_count",
                "blink100/probe_count" -> "blink100_count",
                "probe_state" -> "state",
                "probe_initDone" -> "initDone",
                "probe_alignedAt" -> "alignedAt",
                "probe_tap" -> "tap",
                "probe_calibPlane" -> "calibPlane",
                "probe_calibrating" -> "calibrating",
                "probe_lockedPlane" -> "lockedPlane",
                "probe_lockedTap" -> "lockedTap",
                "probe_recent" -> "recent",
                "probe_captured" -> "captured",
                "probe_checked" -> "checked",
                "probe_failTaps" -> "failTaps",
                "probe_roundIn" -> "roundIn",
                "probe_timer" -> "timer",
                "probe_dqIn" -> "dqIn",
                "probe_rounds" -> "rounds",
                "probe_misses" -> "misses",
                "probe_blocks" -> "blocks",
                "probe_combo" -> "combo",
                "probe_missPat" -> "missPat",
                "probe_missAddr" -> "missAddr",
                "probe_missTap" -> "missTap",
                "probe_passMask" -> "passMask",
                "probe_missMask" -> "missMask"
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

        test("the constraints are the parts that declare pins, one file each"):
            val (board, ddr3, list) = xdcArtifacts
            assert(list == "board.xdc\nddr3.xdc\n")
            // The level's own file: the configuration bank, the oscillator, the button, the LEDs — and the exceptions
            // their domains carry.
            assert(board.contains("create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN J19 [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN L18 [get_ports RESET]"))
            assert(board.contains("set_property PACKAGE_PIN M18 [get_ports LED1]"))
            assert(board.contains("set_false_path -from [get_ports RESET]"))
            // The oscillator's domain observes, and one direction is enough: everything it drives, it drives inside
            // itself. Nothing in this file names a clock the design asked for — the crossings that used to be excused
            // grid by grid are gone with the shared reset level they were about (a domain waits for the button's *pin*
            // now), and an exception on a grid nobody crosses would only be somewhere for a later path to hide.
            assert(board.contains("set_false_path -to [get_clocks -quiet {CLK_50M}]"))
            assert(!board.contains("clk_wiz"))
            // The bus's own file: the pin map of the board's wiring, its termination, and the exceptions the pads'
            // serializer stages need — the level pins' own among them, matched by the register the level pads share.
            assert(ddr3.contains("set_property INTERNAL_VREF 0.750 [get_iobanks 35]"))
            assert(ddr3.contains("set_property PACKAGE_PIN K2 [get_ports {DDR3_DQS_P[1]}]"))
            assert(ddr3.contains("set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[15]}]"))
            assert(ddr3.contains("set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_CK_P}]"))
            assert(ddr3.contains("set_multicycle_path 4 -setup -to [get_pins -hierarchical {*T1 *T2 *T3 *T4}]"))
            assert(
                ddr3.contains(
                    "set_multicycle_path 2 -setup -to [get_pins -hierarchical {*levelDrive/D1 *levelDrive/D2}]"
                )
            )
            // The serializers' resets: one pattern, and the leading wildcard is the *index-bearing* part of the name
            // (`dqDrive0`, `padDrive14`, …) — a `/`-bearing pattern after `-hierarchical` names nothing at all, which
            // is how the level pins' exception above was silently dropped once.
            assert(ddr3.contains("set_false_path -to [get_pins -hierarchical {*Drive*/RST}]"))
            assert(ddr3.contains("set_false_path -to [get_ports {DDR3_CK_P DDR3_CK_N}]"))
            // Each file binds its own part's pins, and this is the split: the level's four here, the whole bus there.
            assert(constrained(board) == Set("CLK_50M", "RESET", "LED1", "LED2"))
            assert(constrained(ddr3) == topPorts -- Set("CLK_50M", "RESET", "LED1", "LED2"))

        test("what the constraints bind is exactly the pins the top declares"):
            val (board, ddr3, _) = xdcArtifacts
            // Both directions of the contract: a line for a port the design does not have is an error in Vivado, and a
            // port with no line is a pin the tools would place themselves — so the two sets are one set.
            assert(constrained(board + ddr3) == topPorts)

        test("the pads' bodies are the dump's, and each one is the module the top instantiates"):
            val (dq, dqs, addr, level, ck, idelay, list) = padArtifacts
            // One file per module the design used, written beside the RTL and listed in its file list: the simulator
            // and the synthesizer compile exactly what the top instantiated (`Ddr3Pads`).
            assert(
                list == Seq(
                    "Ddr3IdelayCtrl.sv",
                    "Ddr3DqPads.sv",
                    "Ddr3DqsPads.sv",
                    "Ddr3CkPads.sv",
                    "Ddr3SdrPads15.sv",
                    "Ddr3SdrPads3.sv",
                    "Ddr3SdrPads2.sv",
                    "Ddr3SdrPads1.sv",
                    "Ddr3LevelPads.sv"
                )
            )
            // The data lanes: the pad is the analog pin, and every lane has its own delay, capture and serializer —
            // the tap load included, on the controller's own clock.
            assert(
                TestFlow.ports(dq, "Ddr3DqPads").keySet == Set(
                    "DQ",
                    "dqOut",
                    "oe",
                    "tap",
                    "clk400",
                    "clk200Inv",
                    "clk400Dqs",
                    "clk200Dqs",
                    "ringReset",
                    "dqIn"
                )
            )
            assert(dq.contains("inout [15:0] DQ"))
            assert(dq.split("IDELAYE2").length - 1 == 16)
            assert(dq.split("ISERDESE2").length - 1 == 16)
            assert(dq.split("OSERDESE2").length - 1 == 16)
            assert(dq.split("IOBUF ").length - 1 == 16)
            // The taps are the sweep's hand on the delays, loaded on the controller's clock from the value itself.
            assert(dq.contains("always @(posedge clk200Dqs)"))
            assert(dq.contains(".LD(delayLoad)"))
            // The strobes: the pair is the analog pin, and only the write side exists.
            assert(dqs.contains("inout [1:0] DQS_P"))
            assert(dqs.split("OBUFTDS").length - 1 == 2)
            assert(!dqs.contains("ISERDESE2"))
            // The command side: one serializer per pin, each duplicating its levels into a whole beat.
            assert(addr.contains("output [14:0] pad"))
            assert(addr.split("OSERDESE2").length - 1 == 15)
            assert(addr.contains(".D1(levels[0])") && addr.contains(".D3(levels[1])"))
            // The housekeeping pins and the device's clock: the registers the exceptions above name.
            assert(level.contains("ODDR"))
            assert(level.contains("levelDrive"))
            assert(ck.contains("OBUFDS"))
            assert(ck.contains(".D1(1'b1)"))
            // The calibration group: the one calibrator, and the group the lanes' delays state.
            assert(idelay.contains("IDELAYCTRL ctrl"))
            assert(idelay.contains("IODELAY_GROUP = \"DDR3_DQ_35\""))
