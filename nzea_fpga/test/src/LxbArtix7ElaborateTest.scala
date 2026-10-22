package nzea_fpga

import utest.*

import nzea_device.devices.{Blink, BlinkParameter}
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

    /** The clock plan the design asks for, as its own requests state it ([[nzea_fpga.Clocks]]): the core's grid (the
      * panel's 100 MHz is the same key, so one slot serves both), then the DDR3 device's five — the pads' load clock
      * and the tap reference, the controller's, the command and data sides' 400 MHz pair, and the capture's half period
      * ([[nzea_fpga.device.ddr3.Ddr3Clocks]]). A phase is why the plan needs the MMCM: the PLL has none to state.
      *
      * The order is the plan's own — `clk_out1` first — and it is pinned here because it is what names the wizard's
      * slots, which the constraints and the IP's own configuration follow.
      */
    private val clockPlan = ClockPlan(
        50.0,
        Seq(
            ClockOut(100.0, 0.0),
            ClockOut(200.0, 0.0),
            ClockOut(200.0, 45.0),
            ClockOut(400.0, 0.0),
            ClockOut(400.0, 90.0),
            ClockOut(200.0, 180.0)
        )
    )

    /** The probes the board watches, as its `mark` calls state them: each name a waveform shows and its width, in the
      * order the statements were made. The snapshots below are taken in the dump's own window, so every test reads this
      * dump's table.
      */
    private val watched = Seq(
        // The reset network: the reasons as they are produced, and the domains as their registers see them.
        "buttonPin" -> 1,
        "board50Reset" -> 1,
        // The loader's own account of a load, and the line's bytes (`BootDdr` declares both where they live and binds
        // the receiver's beside its instance).
        "bootState" -> 3,
        "bootMagic" -> 32,
        "bootAddr" -> 32,
        "bootWords" -> 32,
        "bootWritten" -> 32,
        "rx_data" -> 8,
        "rx_valid" -> 1,
        // The device's own account of the bus it runs ([[nzea_fpga.device.ddr3.Ddr3]] declares these where they live):
        // the bring-up's state and alignment, the capture word's sixteen samples, and the accesses themselves.
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
        // The memory's own readiness, then the network's remaining reasons and domains.
        "ddr3TapsReady" -> 1,
        "ddr3Ready" -> 1,
        "ddr3Reset" -> 1,
        "bootHoldN" -> 1,
        "bootReset" -> 1,
        "pllLocked" -> 1,
        "board100Reset" -> 1,
        "coreReset" -> 1,
        "timeReset" -> 1,
        // The loaded program's own verdict, marked where the top takes it (the tile's finisher).
        "finished" -> 1
    )

    /** What the debug script resolves each probe by: a marked signal by leaf name (all but the loader's receiver
      * declarations, which are *bound* at their instance and so carry a path) — the pattern says which: a binding's is
      * the instance's path (`*<instance>/<net>*`).
      */
    private val patterns = Seq(
        "*probe_buttonPin*",
        "*probe_board50Reset*",
        "*probe_bootState*",
        "*probe_bootMagic*",
        "*probe_bootAddr*",
        "*probe_bootWords*",
        "*probe_bootWritten*",
        "*rx/probe_data*",
        "*rx/probe_valid*",
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
        "*probe_ddr3TapsReady*",
        "*probe_ddr3Ready*",
        "*probe_ddr3Reset*",
        "*probe_bootHoldN*",
        "*probe_bootReset*",
        "*probe_pllLocked*",
        "*probe_board100Reset*",
        "*probe_coreReset*",
        "*probe_timeReset*",
        "*probe_finished*"
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
    private val (xdcFiles, xdcList) =
        val _ = sv
        val dir = os.temp.dir(prefix = "nzea-xdc")
        try
            DumpRegistry.emitAll(dir)
            (
                Seq("board", "ddr3", "busCdc", "uart").map(owner => owner -> os.read(dir / s"$owner.xdc")).toMap,
                os.read(dir / "xcdfiles.f")
            )
        finally os.remove.all(dir)

    /** The dump's file list, from the same window as the constraints: the modules the top's build instantiates but the
      * circuit does not define are written beside the RTL and listed there, at the shape each was built with.
      */
    private val fileList: Seq[String] =
        val _ = sv
        val dir = os.temp.dir(prefix = "nzea-files")
        try
            DumpRegistry.emitAll(dir)
            os.read(dir / "filelist.f").linesIterator.toSeq
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

    /** One module's text in the linked lowering: its header to the `endmodule` that closes it. */
    private def moduleText(name: String): String =
        val start = sv.indexOf(s"module $name(")
        require(start >= 0, s"no module $name in the lowering")
        sv.substring(start, sv.indexOf("\nendmodule", start))

    /** How many signals one module declares watchable: one `mark_debug` per declaration. */
    private def markCount(name: String): Int = moduleText(name).split("mark_debug").length - 1

    val tests = Tests:

        test("the top's ports are the board's pins, and nothing else"):
            assert(
                TestFlow.ports(sv, "Top") == Map(
                    "CLK_50M" -> 1,
                    "RESET" -> 1,
                    "LED1" -> 1,
                    "LED2" -> 1,
                    "UART_RX" -> 1,
                    "UART_TX" -> 1,
                    "DDR3_CK_P" -> 1,
                    "DDR3_CK_N" -> 1,
                    "DDR3_ADDR" -> 15,
                    "DDR3_BA" -> 3,
                    "DDR3_RAS_N" -> 1,
                    "DDR3_CAS_N" -> 1,
                    "DDR3_WE_N" -> 1,
                    "DDR3_CKE" -> 1,
                    "DDR3_ODT" -> 1,
                    "DDR3_RESET_N" -> 1,
                    "DDR3_DM" -> 2,
                    "DDR3_DQ" -> 16,
                    "DDR3_DQS_P" -> 2,
                    "DDR3_DQS_N" -> 2
                )
            )

        test("the top is the design: the loader, the core it fills, and the memory between them — an ILA is not"):
            assert(sv.contains("module Top("))
            assert(sv.contains("Bufg clk50Buffer ("))
            assert(sv.contains("ResetSync board50Sync ("))
            assert(sv.contains("ResetSync board100Sync ("))
            assert(sv.contains("ResetSync bootSync ("))
            assert(sv.contains("ResetSync ddr3Sync ("))
            assert(sv.contains("ResetSync coreSync ("))
            assert(sv.contains("ResetSync timeSync ("))
            assert(sv.contains("Blink blink50 (") && sv.contains("Blink blink100 ("))
            // The IP keeps its instance name (it names nets and shows up in waveforms).
            assert(sv.contains("clk_wiz_0 clockingWizard ("))
            // The loader: the line it listens on, the grid it shares with the core, the hold it states and the boot
            // domain it runs in — the pin, the grid and the reason all land on the ports its own needs declared.
            assert(sv.contains("BootDdr boot ("))
            assert(
                sv.matches(
                    "(?s).*BootDdr boot \\(.*\\.UART_RX\\s+\\(UART_RX\\).*\\.clk100_0\\s+\\(_clockingWizard_clk_out1\\).*"
                )
            )
            assert(
                sv.matches(
                    "(?s).*BootDdr boot \\(.*\\.bootHoldN\\s+\\(_boot_bootHoldN\\).*\\.resetN\\s+\\(_bootSync_syncN\\).*"
                )
            )
            // The core: on the same grid, and reset by the network's own core domain (active-high at the tile's
            // boundary, which is why it reads as the inverted release).
            assert(sv.contains("NzeaTile tile ("))
            assert(
                sv.matches(
                    "(?s).*NzeaTile tile \\(.*\\.clock\\s+\\(_clockingWizard_clk_out1\\).*\\.reset\\s+\\(~_coreSync_syncN\\).*"
                )
            )
            // The counter's own release: the platform's time outlives a load, so it is the time domain's, not the core's.
            assert(sv.matches("(?s).*NzeaTile tile \\(.*\\.timeReset\\s+\\(~_timeSync_syncN\\).*"))
            // The memory: the tile's window on its core side and the loader on its load side, told apart by the boot
            // domain's own reset and by the core's own reset (`coreHeld`).
            assert(sv.contains("DdrMemory memory ("))
            assert(
                sv.matches(
                    "(?s).*DdrMemory memory \\(.*\\.nearResetN\\s+\\(_bootSync_syncN\\).*\\.coreHeld\\s+\\(~_coreSync_syncN\\).*"
                )
            )
            assert(sv.matches("(?s).*DdrMemory memory \\(.*\\.core_req_valid\\s+\\(_tile_ram_req_valid\\).*"))
            assert(sv.matches("(?s).*DdrMemory memory \\(.*\\.load_req_valid\\s+\\(_boot_load_req_valid\\).*"))
            assert(sv.matches("(?s).*NzeaTile tile \\(.*\\.ram_resp_valid\\s+\\(_memory_core_resp_valid\\).*"))
            assert(!sv.contains("RamFabricSlave mem ("))
            // The tile's own devices (its own file names each one generically), and no device for the window: the RAM
            // this level answers is not built inside the tile.
            assert(sv.contains("Uart device ("))
            assert(sv.contains("SifiveTestFinisher device_0 ("))
            assert(sv.contains("Clint device_1 ("))
            assert(!sv.contains("RamFabricSlave ram ("))
            assert(!sv.contains("u_ila_0"))

        test("the oscillator feeds the wizard directly, and the design's own logic off a global buffer"):
            // The generator's input is the pin as it is — its compensation is about the IO buffer's path — so the tool
            // has no reason to put its own buffer there (a PLL fed through one fails the IO-clock DRC: REQP-1712). The
            // level's own logic (the reset synchronizers, the LED panel, the ILA's clock) runs off a global buffer of
            // the pin.
            assert(sv.contains(".I (CLK_50M)"))
            assert(sv.contains(".clk_in1  (CLK_50M)"))
            assert(sv.contains(".clock (_clk50Buffer_O)"))
            assert(sv.matches("(?s).*\\.clock50\\s+\\(_clk50Buffer_O\\).*"))
            // The panel's own 100 MHz is the core's grid — one key, one wizard slot — so it is the same net.
            assert(sv.matches("(?s).*\\.clock100\\s+\\(_clockingWizard_clk_out1\\).*"))
            assert(sv.matches("(?s).*\\.resetN50\\s+\\(_board50Sync_syncN\\).*"))
            assert(sv.matches("(?s).*\\.resetN100\\s+\\(_board100Sync_syncN\\).*"))

        test("the reset network is each domain's own conjunction, on its own clock"):
            // The oscillator's domain: the pin as it is, and nothing else.
            assert(sv.matches("(?s).*ResetSync board50Sync \\(.*\\.rawN  \\(RESET\\).*"))
            // The two reasons the generated domains share — the button's *pin* and the wizard's lock: a domain waits for
            // the raw reason, never for a level another domain happened to clean. Composed once and shared (the tools
            // fold identical logic), which is why the net is named for what it joins rather than for the domain that
            // happened to ask first.
            assert(sv.matches("(?s).*wire\\s+buttonPinAndPllLocked = RESET & _clockingWizard_locked;.*"))
            assert(sv.matches("(?s).*ResetSync board100Sync \\(.*\\.rawN  \\(buttonPinAndPllLocked\\).*"))
            // The loader waits for the memory besides: the frames it writes land in the DDR, so the controller must be
            // calibrated before the line takes one.
            assert(
                sv.matches("(?s).*ResetSync bootSync \\(.*\\.rawN  \\(buttonPinAndPllLocked & _memory_ddr3Ready\\).*")
            )
            // The core's domain adds the loader's hold: a load is what holds the core, and the release is this
            // synchronizer's, on the grid the core itself runs on.
            assert(sv.matches("(?s).*ResetSync coreSync \\(.*\\.clock \\(_clockingWizard_clk_out1\\).*"))
            assert(
                sv.matches(
                    "(?s).*ResetSync coreSync \\(.*\\.rawN  \\(buttonPinAndPllLocked & _boot_bootHoldN & _memory_ddr3Ready\\).*"
                )
            )
            // The platform's time outlives both a load and the calibration — the one domain that waits for the board's
            // reasons alone, which is why `mtime` is not set back by either.
            assert(sv.matches("(?s).*ResetSync timeSync \\(.*\\.clock \\(_clockingWizard_clk_out1\\).*"))
            assert(sv.matches("(?s).*ResetSync timeSync \\(.*\\.rawN  \\(buttonPinAndPllLocked\\).*"))
            // The device's own: the pads' calibration first, then the controller's readiness (`Ddr3Side` states both).
            assert(sv.matches("(?s).*ResetSync ddr3Sync \\(.*\\.clock \\(_clockingWizard_clk_out3\\).*"))
            assert(
                sv.matches(
                    "(?s).*ResetSync ddr3Sync \\(.*\\.rawN  \\(buttonPinAndPllLocked & _memory_ddr3TapsReady\\).*"
                )
            )
            // Every reason and every domain is watchable: the first question a reset network raises is which reason holds.
            for probe <- Seq(
                    "buttonPin",
                    "pllLocked",
                    "bootHoldN",
                    "board50Reset",
                    "board100Reset",
                    "bootReset",
                    "coreReset",
                    "timeReset"
                )
            do assert(sv.contains(s"probe_$probe"))

        test("the loaded program's verdict is the board's, not the simulator's"):
            // The tile shows the finisher's level at its boundary and the panel spends it: LED2 is the panel's own output,
            // held in its lit phase — at the panel's duty — once the program has written the finisher. The top does not
            // tie the pin low (that would be full brightness rather than the dimmed LED the board blinks with), so the
            // pin is the panel's connection and nothing else drives it.
            assert(!sv.contains("assign LED2"))
            assert(
                sv.matches(
                    "(?s).*Blinkers blinkers \\(.*\\.finished\\s+\\(_tile_finisher_finished\\).*\\.led2\\s+\\(LED2\\).*"
                )
            )

        // Vivado synthesis silently drops SystemVerilog `bind`, so a layer-hosted observer vanishes from the netlist
        // without a warning (`nzea_rtl.probe.ProbeLayerTest` has the mechanism and the experiment). What watches a board
        // signal instead is `mark_debug`.
        test("the board's ILA is not in the RTL: the netlist debug flow builds it"):
            assert(!sv.contains("bind "))
            assert(!sv.contains("u_ila_0"))
            assert(!sv.contains("Top_debug"))

        test("each module marks its own signals for the netlist debug flow, and the top keeps its clock"):
            // One declaration, one marked wire, in the module that owns the signal: the top's twelve (the reset
            // network's reasons and domains, the memory's own three, and the program's verdict), the loader's five and
            // its receiver's two. What the core declares is its own business: its marks are inert here, because the
            // ILA's table is the board's ([[Ila]] connects the probes declared at this level's modules, no one else's).
            assert(sv.contains("module BootDdr("))
            assert(sv.contains("module UartRx("))
            assert(markCount("Top") == 12)
            assert(markCount("BootDdr") == 5)
            assert(markCount("UartRx") == 2)
            // The core's clock is the oscillator's own, buffered — not one of the wizard's: its logic closes at 50 MHz,
            // and those edges line up with the fabric's instead of sitting a quarter period off them.
            assert(sv.contains("assign ila_clock = _clk50Buffer_O"))

        test("a declaration is the module's own: the wire and the attribute are in its Verilog, ports unchanged"):
            assert(blinkSv.contains("(* mark_debug = \"true\" *)"))
            assert(blinkSv.contains("probe_count"))
            assert(
                TestFlow.ports(blinkSv, "Blink") == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "steady" -> 1,
                    "led" -> 1,
                    "count" -> 27
                )
            )

        test("the probe table is the wiring's: what is watched, as what, and how it resolves"):
            assert(probeNames == watched.map(_._1))
            assert(probeWidths == watched.map(_._2))
            assert(probePatterns == patterns)

        test("a binding names a declaration that exists"):
            // The bound probes are in the table, so `watch` found the receiver's declarations; a binding of a name no
            // module declared fails the elaboration instead of reaching a build.
            assert(probePatterns.contains("*rx/probe_data*"))
            assert(probePatterns.contains("*rx/probe_valid*"))

        test("the clocking wizard's tcl follows the plan"):
            // The design's own requests are the plan: two keys, in the order the slots are named from ([[Clocks]]). A
            // wiring statement that moved a grid would fail here instead of silently re-slotting the IP and the
            // constraints that name a grid by the slot the plan gives it.
            assert(plannedClocks == clockPlan.outputs)
            val tcl = ClockingWizard.tcl(ClockingWizardParameter(Clocks.ipName, clockPlan))
            assert(tcl.contains("module_name clk_wiz_0"))
            // The device's phases are what the plan needs, and the PLL has none to state — so the search serves this plan
            // with the MMCM.
            assert(tcl.contains("CONFIG.PRIMITIVE {MMCM}"))
            assert(tcl.contains("CONFIG.PRIM_IN_FREQ {50.000}"))
            assert(tcl.contains("CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {100.000}"))
            assert(tcl.contains("CONFIG.CLKOUT2_REQUESTED_OUT_FREQ {200.000}"))
            assert(ClockPlan.parse("50:100,200,200@45,400,400@90,200@180").exists(_ == clockPlan))

        test("the primitive is what the frequencies need, not a choice"):
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(100.0, 0.0))).primitive == ClockPrimitive.Pll)
            // The board's own build: the core's 100 MHz and the device's phased 200/400 MHz, all from the oscillator.
            assert(clockPlan.primitive == ClockPrimitive.Mmcm)
            // A fractional ratio is not what takes the PLL away: 148.5 = 27 × 33 / 6 is an integer solution (VCO 891).
            assert(ClockPlan(27.0, Seq(ClockOut(148.5, 0.0))).primitive == ClockPrimitive.Pll)
            // And an integer-looking plan is not what keeps it: 100 → 33.3 has no exact PLL solution.
            assert(ClockPlan(100.0, Seq(ClockOut(33.3, 0.0))).primitive == ClockPrimitive.Mmcm)
            // A phase takes the plan away from the PLL, which has none to state.
            assert(ClockPlan(50.0, Seq(ClockOut(200.0, 0.0), ClockOut(400.0, 90.0))).primitive == ClockPrimitive.Mmcm)
            assert(ClockPrimitive.mmcmSolution(ClockPlan(50.0, Seq(ClockOut(400.0, 90.0)))).isDefined)
            // The wizard's IP is the same either way; only its configuration states which block.
            assert(
                ClockingWizard
                    .tcl(ClockingWizardParameter("clk_wiz_0", ClockPlan(100.0, Seq(ClockOut(33.3, 0.0)))))
                    .contains("CONFIG.PRIMITIVE {MMCM}")
            )

        test("the clock plan's flag spelling round-trips"):
            assert(ClockPlan.parse("50:100,200,200@45,400,400@90,200@180") == Right(clockPlan))
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
            // it does not — the key is the probe's search pattern without its wildcards, which is what the export names
            // the net after.
            for (pattern, name) <- patterns.zip(watched.map(_._1)) do
                assert(map.contains(s"${pattern.stripPrefix("*").stripSuffix("*")} $name"))
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
            // One file per part of the design that declares pins, in the order those parts registered: the board's own
            // pins, the DDR3 bus, the crossing (which declares no pin — only timing), and the serial line.
            assert(xdcList == "board.xdc\nddr3.xdc\nbusCdc.xdc\nuart.xdc\n")
            val board = xdcFiles("board")
            assert(board.contains("create_clock -period 20.000 -name CLK_50M [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN J19 [get_ports CLK_50M]"))
            assert(board.contains("set_property PACKAGE_PIN L18 [get_ports RESET]"))
            assert(board.contains("set_property PACKAGE_PIN M18 [get_ports LED1]"))
            assert(board.contains("set_false_path -from [get_ports RESET]"))
            assert(board.contains("set_false_path -to [get_clocks -quiet {CLK_50M}]"))
            assert(!board.contains("clk_wiz"))
            // The bus: the reference's pin map on the top's ports, its I/O standards and termination, and the exceptions
            // its serializer stages and its capture enable need.
            val ddr3 = xdcFiles("ddr3")
            assert(ddr3.contains("set_property PACKAGE_PIN P5 [get_ports DDR3_CK_P]"))
            assert(ddr3.contains("set_property IOSTANDARD DIFF_SSTL15 [get_ports {DDR3_DQS_P[*]}]"))
            assert(ddr3.contains("set_property IN_TERM UNTUNED_SPLIT_50 [get_ports {DDR3_DQ[0]}]"))
            assert(ddr3.contains("set_property MAX_FANOUT 64 [get_cells -hierarchical {*captureOpen_reg*}]"))
            // The crossing declares no pin: it states what the grids' phase relation does not (the payload is held by the
            // handshake, so it is a multicycle; the flips are the synchronizer's).
            val busCdc = xdcFiles("busCdc")
            assert(
                busCdc.contains("set_multicycle_path 2 -setup -from [get_cells -hierarchical {*nearReqPayload_*reg*}]")
            )
            assert(busCdc.contains("set_false_path -from [get_cells -hierarchical {*nearReqToggle_reg*}]"))
            // The line: the vendor's pins, on the fabric's receive direction and its transmit one.
            val uart = xdcFiles("uart")
            assert(uart.contains("set_property PACKAGE_PIN U2 [get_ports UART_RX]"))
            assert(uart.contains("set_property IOSTANDARD LVCMOS33 [get_ports UART_RX]"))
            assert(uart.contains("set_property PACKAGE_PIN V2 [get_ports UART_TX]"))
            assert(uart.contains("set_property IOSTANDARD LVCMOS33 [get_ports UART_TX]"))
            assert(constrained(board) == Set("CLK_50M", "RESET", "LED1", "LED2"))
            assert(constrained(uart) == Set("UART_RX", "UART_TX"))

        test("what the constraints bind is exactly the pins the top declares"):
            // Both directions of the contract: a line for a port the design does not have is an error in Vivado, and a
            // port with no line is a pin the tools would place themselves — so the two sets are one set.
            assert(xdcFiles.values.map(constrained).reduce(_ ++ _) == topPorts)

        test("the bodies the design instantiates but does not define are the dump's, at the shape it built"):
            // The board's global buffer, the memory's pads and their delay controller, and the tile's own memories are
            // written beside the RTL and listed in the emitted list (one pads module per pin count).
            assert(fileList.contains("Bufg.sv"))
            assert(fileList.contains("Ddr3IdelayCtrl.sv"))
            assert(fileList.contains("Ddr3DqPads.sv"))
            assert(fileList.contains("Ddr3DqsPads.sv"))
            assert(fileList.contains("Ddr3SdrPads15.sv"))
            assert(fileList.contains("Ddr3LevelPads.sv"))
            assert(fileList.exists(_.startsWith("SyncRam_")))
