package nzea_fpga

import utest.*

import nzea_device.devices.{Blink, BlinkParameter}
import nzea_fpga.device.ddr3.{Ddr3Clocks, Ddr3Pads}
import nzea_fpga.ip.{ClockOut, ClockPlan, ClockPrimitive, ClockingWizard, ClockingWizardParameter, Ila}
import nzea_rtl.{DumpRegistry, TestFlow}

/** What the board top's shape is: its pins (the XDC's names), the modules and IP it instantiates, the tcl the IPs are
  * created from, and the artifacts the ILA's probe table produces. Those are contracts with things outside the RTL —
  * the constraints, the Vivado project, the implementation run, the capture flow — so they are pinned here rather than
  * left to a build to notice.
  */
object LxbArtix7ElaborateTest extends TestSuite:

    /** The board as the bring-up build is made. */
    private val parameter = TopParameter()

    private lazy val sv = TestFlow.lower(
        Top.moduleName(parameter),
        cfg => Top.main(Array("config", cfg.toString)),
        cfg => Top.main(Array("design", cfg.toString))
    )

    /** The clock plan the design asks for, as its own requests state it ([[nzea_fpga.Clocks]]): the bus's five grids —
      * the device states them where its instances are named ([[Ddr3Side]]) — and the level's own 100 MHz. A phase takes
      * the plan away from the PLL (which has none to state), so the wizard the design builds is the MMCM.
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
      * order the statements were made. The snapshots below are taken in the dump's own window, so every test reads this
      * dump's table.
      */
    private val watched = Seq(
        // The reset network: every reason as it is produced, and every domain as its registers see it (1 = in reset).
        "buttonPin" -> 1,
        "board50Reset" -> 1,
        "state" -> 6,
        "initDone" -> 1,
        "alignedAt" -> 6,
        "halfAt" -> 6,
        "tap" -> 5,
        "calibPlane" -> 2,
        "calibrating" -> 1,
        "calibrated" -> 1,
        "finalRound" -> 1,
        "finalPass" -> 1,
        "alignGot" -> 1,
        "alignLocked" -> 6,
        "lockedPlane" -> 2,
        "lockedTap" -> 5,
        "writeShift" -> 2,
        "recent" -> 96,
        // The window a word at a time (newest first): one probe that wide reads as zeros on the board, so the table
        // carries the thirteen words the stream and the check are both read in.
        "captured00" -> 64,
        "captured01" -> 64,
        "captured02" -> 64,
        "captured03" -> 64,
        "captured04" -> 64,
        "captured05" -> 64,
        "captured06" -> 64,
        "captured07" -> 64,
        "captured08" -> 64,
        "captured09" -> 64,
        "captured10" -> 64,
        "captured11" -> 64,
        "captured12" -> 64,
        "failTaps" -> 96,
        "roundIn" -> 7,
        "accesses" -> 32,
        "respValid" -> 1,
        "respData" -> 128,
        "dmOut" -> 8,
        "timer" -> 24,
        "dqIn" -> 64,
        "phase" -> 3,
        "step" -> 6,
        "issued" -> 32,
        "errors" -> 32,
        "done" -> 1,
        "firstBadAddr" -> 32,
        "firstBadExp" -> 128,
        "firstBadGot" -> 128,
        // The bus's own reason is declared where the logic is wired, after the device that produces it elaborated.
        "ddr3TapsReady" -> 1,
        "ddr3Reset" -> 1,
        "pllLocked" -> 1,
        "board100Reset" -> 1,
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
        "*probe_state*",
        "*probe_initDone*",
        "*probe_alignedAt*",
        "*probe_halfAt*",
        "*probe_tap*",
        "*probe_calibPlane*",
        "*probe_calibrating*",
        "*probe_calibrated*",
        "*probe_finalRound*",
        "*probe_finalPass*",
        "*probe_alignGot*",
        "*probe_alignLocked*",
        "*probe_lockedPlane*",
        "*probe_lockedTap*",
        "*probe_writeShift*",
        "*probe_recent*",
        "*probe_captured00*",
        "*probe_captured01*",
        "*probe_captured02*",
        "*probe_captured03*",
        "*probe_captured04*",
        "*probe_captured05*",
        "*probe_captured06*",
        "*probe_captured07*",
        "*probe_captured08*",
        "*probe_captured09*",
        "*probe_captured10*",
        "*probe_captured11*",
        "*probe_captured12*",
        "*probe_failTaps*",
        "*probe_roundIn*",
        "*probe_accesses*",
        "*probe_respValid*",
        "*probe_respData*",
        "*probe_dmOut*",
        "*probe_timer*",
        "*probe_dqIn*",
        "*probe_phase*",
        "*probe_step*",
        "*probe_issued*",
        "*probe_errors*",
        "*probe_done*",
        "*probe_firstBadAddr*",
        "*probe_firstBadExp*",
        "*probe_firstBadGot*",
        "*probe_ddr3TapsReady*",
        "*probe_ddr3Reset*",
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
      * read here, before that elaboration runs.
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
                os.read(dir / "Ddr3DmPads.sv"),
                os.read(dir / "Ddr3LevelPads.sv"),
                os.read(dir / "Ddr3CkPads.sv"),
                os.read(dir / "Ddr3IdelayCtrl.sv"),
                os.read(dir / "filelist.f").linesIterator.toSeq
            )
        finally os.remove.all(dir)

    private val topPorts: Set[String] = TestFlow.ports(sv, "Top").keySet

    /** The clock plan the design asked for, read in the dump's own window: a later elaboration in this JVM resets the
      * registry the requests were collected in ([[Clocks]]).
      */
    private val plannedClocks = { val _ = sv; Clocks.planned }

    /** What a constraint file binds, from its `get_ports` targets: brace-grouped or bare, a vector's index dropped to
      * the port the index is of.
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

        test("the top is the design, and the logic's pads are its own modules — an ILA is not"):
            assert(sv.contains("module Top("))
            assert(sv.contains("ResetSync board50Sync ("))
            assert(sv.contains("ResetSync board100Sync ("))
            assert(sv.contains("ResetSync ddr3Sync ("))
            assert(sv.contains("Blink blink50 (") && sv.contains("Blink blink100 ("))
            // The IP keeps its instance name (it names nets and shows up in waveforms).
            assert(sv.contains("clk_wiz_0 clockingWizard ("))
            // The top carries one logic and wires what that logic stated: the bus's pins, the grids it runs on and the
            // domain it resets in land on the soak, and the device's own instances are named inside it — no pin of the
            // bus appears in the top's own text.
            assert(sv.contains("Ddr3Soak soak ("))
            assert(
                sv.matches(
                    "(?s).*Ddr3Soak soak \\(.*\\.DDR3_DQ\\s+\\(DDR3_DQ\\).*\\.clk200_45\\s+\\(_clockingWizard_clk_out2\\).*"
                )
            )
            assert(sv.matches("(?s).*Ddr3Soak soak \\(.*\\.ddr3TapsReady\\s+\\(_soak_ddr3TapsReady\\).*"))
            assert(sv.matches("(?s).*Ddr3Soak soak \\(.*\\.resetN\\s+\\(_ddr3Sync_syncN\\).*"))
            assert(sv.contains("Ddr3 ddr3 ("))
            assert(sv.matches("(?s).*Ddr3 ddr3 \\(.*\\.clock\\s+\\(clk200_45\\).*\\.resetN\\s+\\(resetN\\).*"))
            assert(sv.matches("(?s).*Ddr3 ddr3 \\(.*\\.bus_req_ready\\s+\\(_ddr3_bus_req_ready\\).*"))
            // The pads: modules of the circuit, instantiated by the logic that owns the chip, with the analog nets
            // attached to the top's own ports.
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
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _soak_ddr3TapsReady)"))
            assert(sv.contains(".ringReset (~resetN)"))
            assert(!sv.contains("u_ila_0"))

        test("the oscillator feeds the wizard directly, and the design's own logic off a global buffer"):
            // The generator's input is the pin as it is — its compensation is about the IO buffer's path — so the tool
            // has no reason to put its own buffer there (a PLL fed through one fails the IO-clock DRC: REQP-1712). The
            // level's own logic (the reset synchronizer, the LED panel) runs off a global buffer of the pin.
            assert(sv.contains("Bufg clk50Buffer ("))
            assert(sv.contains(".I (CLK_50M)"))
            assert(sv.contains(".clk_in1  (CLK_50M)"))
            assert(sv.contains(".clock (_clk50Buffer_O)"))
            assert(sv.matches("(?s).*\\.clock50\\s+\\(_clk50Buffer_O\\).*"))
            assert(sv.matches("(?s).*\\.clock100\\s+\\(_clockingWizard_clk_out6\\).*"))
            assert(sv.matches("(?s).*\\.resetN100\\s+\\(_board100Sync_syncN\\).*"))
            assert(sv.matches("(?s).*\\.resetN50\\s+\\(_board50Sync_syncN\\).*"))
            // The DDR3 domain's reset is its own synchronizer's release: an asynchronous feed from two other domains
            // was the board's worst path (−7 ns at 400 MHz). The pads' tap calibration is one of the reasons it waits
            // for — nothing about the device is touched before the taps are usable — and it crosses in as the logic's
            // own port.
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _soak_ddr3TapsReady)"))
            // The port list's names are column-aligned, so this one's padding grows with the longest name beside it;
            // the connection is what the test is about, not the whitespace.
            assert(sv.matches("(?s).*\\.resetN\\s+\\(_ddr3Sync_syncN\\).*"))

        test("the reset network is each domain's own conjunction, on its own clock"):
            // The oscillator's domain: the pin as it is, and nothing else.
            assert(sv.matches("(?s).*\\.rawN  \\(RESET\\).*"))
            // The two reasons the generated domains share — the button's *pin* and the wizard's lock: a domain waits for
            // the raw reason, never for a level another domain happened to clean. Composed once and shared (the tools
            // fold identical logic), which is why the net is named for what it joins rather than for the domain that
            // happened to ask first.
            assert(sv.matches("(?s).*wire\\s+buttonPinAndPllLocked = RESET & _clockingWizard_locked;.*"))
            assert(sv.matches("(?s).*ResetSync board100Sync \\(.*\\.rawN  \\(buttonPinAndPllLocked\\).*"))
            // The device adds its own ready to the same pair, and its synchronizer runs on the bus's own grid — both
            // stated where the logic's instance is named, with the reason arriving on the logic's own port.
            assert(sv.matches("(?s).*ResetSync ddr3Sync \\(.*\\.clock \\(_clockingWizard_clk_out2\\).*"))
            assert(sv.contains(".rawN  (buttonPinAndPllLocked & _soak_ddr3TapsReady)"))
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
        // without a warning (`nzea_rtl.probe.ProbeLayerTest` has the mechanism and the experiment). What watches a board
        // signal instead is `mark_debug`.
        test("the board's ILA is not in the RTL: the netlist debug flow builds it"):
            assert(!sv.contains("bind "))
            assert(!sv.contains("u_ila_0"))
            assert(!sv.contains("Top_debug"))

        test("the top marks its own signals for the netlist debug flow, and keeps its clock"):
            // One `mark_debug` per marked probe: the reset network's six (three reasons, three domains), the top's own
            // two, the panel's, the bus's and the soak's — the single-file lowering carries them all in their modules'
            // bodies. The bus's count carries the window's thirteen words, since the window is watched a word at a time.
            assert(sv.contains("module Blink("))
            assert(sv.contains("module Ddr3("))
            assert(sv.split("mark_debug").length - 1 == 54)
            // The core's clock is the oscillator's own, buffered — not one of the wizard's: its logic closes at 50 MHz,
            // and those edges line up with the fabric's instead of sitting a quarter period off them.
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
            assert(tcl.contains("CONFIG.PRIMITIVE {MMCM}"))
            assert(tcl.contains("CONFIG.PRIM_IN_FREQ {50.000}"))
            assert(tcl.contains("CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT3_REQUESTED_OUT_FREQ {400.000}"))
            assert(tcl.contains("CONFIG.CLKOUT4_REQUESTED_OUT_FREQ {400.000}"))
            // The phase is requested, not dictated: the wizard's achieved fields are its own generated properties
            // (writing one is rejected — see `ClockingWizard.tcl`).
            assert(tcl.contains("CONFIG.CLKOUT4_REQUESTED_PHASE {90.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_PHASE {45.000}"))
            assert(tcl.contains("CONFIG.CLKOUT5_REQUESTED_OUT_FREQ {200.000}"))
            assert(tcl.contains("CONFIG.CLKOUT5_REQUESTED_PHASE {180.000}"))
            assert(tcl.contains("CONFIG.CLKOUT6_REQUESTED_OUT_FREQ {100.000}"))
            // The command grid's divide is asked for by the capture's `CLKDIV` as well as by `IDELAYCTRL` — the plan
            // keeps the one slot a repeated ask gives it.
            assert(tcl.contains("CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {200.000}"))
            assert(ClockPlan.parse("50:200,200@45,400,400@90,200@180,100").exists(_ == clockPlan))

        test("the primitive is what the frequencies need, not a choice"):
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(100.0, 0.0))).primitive == ClockPrimitive.Pll)
            // A fractional ratio is not what takes the PLL away: 148.5 = 27 × 33 / 6 is an integer solution (VCO 891).
            assert(ClockPlan(27.0, Seq(ClockOut(148.5, 0.0))).primitive == ClockPrimitive.Pll)
            // And an integer-looking plan is not what keeps it: 100 → 33.3 has no exact PLL solution.
            assert(ClockPlan(100.0, Seq(ClockOut(33.3, 0.0))).primitive == ClockPrimitive.Mmcm)
            assert(ClockPlan(50.0, Seq.fill(5)(ClockOut(100.0, 0.0))).primitive == ClockPrimitive.Mmcm)
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(400.0, 90.0))).primitive == ClockPrimitive.Mmcm)
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
            assert(ClockPlan.parse("50:200,100:MMCM").isLeft)
            assert(ClockPlan.parse("50:").isLeft)
            assert(ClockPlan.parse("x:200").isLeft)
            assert(
                intercept[IllegalArgumentException](ClockPlan(50.0, Seq(ClockOut(33.3333, 0.0)))).getMessage
                    .contains("kHz grid")
            )
            assert(ClockOut.parse("400@360").isLeft)
            assert(ClockOut.parse("400@90") == Right(ClockOut(400.0, 90.0)))

        test("the ILA's debug script follows the probe table"):
            val tcl = debugTcl
            assert(tcl.contains("create_debug_core u_ila_0 ila"))
            assert(tcl.contains(s"set_property C_DATA_DEPTH ${ila.depth} [get_debug_cores u_ila_0]"))
            // probe0 comes with the core; every further probe is one more port, at the width declared.
            assert(tcl.split("create_debug_port u_ila_0 probe").length - 1 == watched.size - 1)
            assert(tcl.contains("connect_debug_port u_ila_0/clk [ila_leaf {*ila_clock*} 1]"))
            // Both searches exclude the core's own nets: `connect_debug_port` inserts a net named `<core>_<net>` per
            // probe connected, and a declared name is a prefix of a longer one (`probe_locked` of `probe_lockedPlane`)
            // — an unfiltered search would find the core's net and fail the build on the count, depending on the probe
            // order, which is not something a script should depend on.
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
                "probe_halfAt" -> "halfAt",
                "probe_tap" -> "tap",
                "probe_calibPlane" -> "calibPlane",
                "probe_calibrating" -> "calibrating",
                "probe_calibrated" -> "calibrated",
                "probe_finalRound" -> "finalRound",
                "probe_finalPass" -> "finalPass",
                "probe_alignGot" -> "alignGot",
                "probe_alignLocked" -> "alignLocked",
                "probe_lockedPlane" -> "lockedPlane",
                "probe_lockedTap" -> "lockedTap",
                "probe_recent" -> "recent",
                "probe_captured00" -> "captured00",
                "probe_captured01" -> "captured01",
                "probe_captured02" -> "captured02",
                "probe_captured03" -> "captured03",
                "probe_captured04" -> "captured04",
                "probe_captured05" -> "captured05",
                "probe_captured06" -> "captured06",
                "probe_captured07" -> "captured07",
                "probe_captured08" -> "captured08",
                "probe_captured09" -> "captured09",
                "probe_captured10" -> "captured10",
                "probe_captured11" -> "captured11",
                "probe_captured12" -> "captured12",
                "probe_failTaps" -> "failTaps",
                "probe_roundIn" -> "roundIn",
                "probe_accesses" -> "accesses",
                "probe_respValid" -> "respValid",
                "probe_respData" -> "respData",
                "probe_dmOut" -> "dmOut",
                "probe_phase" -> "phase",
                "probe_step" -> "step",
                "probe_issued" -> "issued",
                "probe_errors" -> "errors",
                "probe_done" -> "done",
                "probe_firstBadAddr" -> "firstBadAddr",
                "probe_firstBadExp" -> "firstBadExp",
                "probe_firstBadGot" -> "firstBadGot"
            )
            for (key, name) <- netKeys do assert(map.contains(s"$key $name"))
            for (name, i) <- watched.map(_._1).zipWithIndex do assert(map.contains(s"probe$i $name"))
            assert(capture.contains(s"CONTROL.TRIGGER_POSITION ${ila.depth / 4}"))
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
            assert(board.contains("create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN J19 [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN L18 [get_ports RESET]"))
            assert(board.contains("set_property PACKAGE_PIN M18 [get_ports LED1]"))
            assert(board.contains("set_false_path -from [get_ports RESET]"))
            assert(board.contains("set_false_path -to [get_clocks -quiet {CLK_50M}]"))
            assert(!board.contains("clk_wiz"))
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
            // The serializers' resets: the leading wildcard is the *index-bearing* part of the name (`dqDrive0`,
            // `padDrive14`, …) — a `/`-bearing pattern after `-hierarchical` names nothing at all, which is how the
            // level pins' exception above was silently dropped once.
            assert(ddr3.contains("set_false_path -to [get_pins -hierarchical {*Drive*/RST}]"))
            assert(ddr3.contains("set_false_path -to [get_ports {DDR3_CK_P DDR3_CK_N}]"))
            assert(constrained(board) == Set("CLK_50M", "RESET", "LED1", "LED2"))
            assert(constrained(ddr3) == topPorts -- Set("CLK_50M", "RESET", "LED1", "LED2"))

        test("what the constraints bind is exactly the pins the top declares"):
            val (board, ddr3, _) = xdcArtifacts
            // Both directions of the contract: a line for a port the design does not have is an error in Vivado, and a
            // port with no line is a pin the tools would place themselves — so the two sets are one set.
            assert(constrained(board + ddr3) == topPorts)

        test("the pads' bodies are the dump's, and each one is the module the logic instantiates"):
            val (dq, dqs, addr, dm, level, ck, idelay, list) = padArtifacts
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
                    "Ddr3SdrPads1.sv",
                    "Ddr3DmPads.sv",
                    "Ddr3LevelPads.sv"
                )
            )
            // The data lanes: the pad is the analog pin, and every lane has its own delay, capture and serializer —
            // the tap load included.
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
            // The capture latches on its `CLKDIVP` divide; `CLKDIV` stays low as MIG's own byte group leaves it —
            // driving both leaves the I/O tile three clocks, which does not route (`Ddr3Pads` keeps both the reference
            // and the measurement).
            assert(dq.contains(".CLK(clk400)"))
            assert(dq.contains(".CLKB(clk400)"))
            assert(dq.contains(".CLKDIV(1'b0)"))
            assert(dq.contains(".CLKDIVP(clk200Inv)"))
            assert(dq.contains("always @(posedge clk200Dqs)"))
            assert(dq.contains(".LD(delayLoad)"))
            assert(dqs.contains("inout [1:0] DQS_P"))
            assert(dqs.split("OBUFTDS").length - 1 == 2)
            assert(!dqs.contains("ISERDESE2"))
            assert(addr.contains("output [14:0] pad"))
            assert(addr.split("OSERDESE2").length - 1 == 15)
            assert(addr.contains(".D1(levels[0])") && addr.contains(".D3(levels[1])"))
            assert(dm.contains("output [1:0] pad"))
            assert(dm.split("OSERDESE2").length - 1 == 2)
            assert(dm.split("OBUF ").length - 1 == 2)
            assert(dm.contains(".D1(levels[0])") && dm.contains(".D4(levels[3])"))
            assert(dm.contains(".D4(levels[7])"))
            assert(dm.contains(".CLK(clk400Dqs)"))
            assert(dm.contains(".TCE(1'b0)"))
            assert(level.contains("ODDR"))
            assert(level.contains("levelDrive"))
            assert(ck.contains("OBUFDS"))
            assert(ck.contains(".D1(1'b1)"))
            assert(idelay.contains("IDELAYCTRL ctrl"))
            assert(idelay.contains("IODELAY_GROUP = \"DDR3_DQ_35\""))
