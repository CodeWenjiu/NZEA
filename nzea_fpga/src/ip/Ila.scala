package nzea_fpga.ip

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.DumpRegistry
import nzea_rtl.probe.{ProbePlace, Probes}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import scala.collection.mutable

/** The fpga level's consumer of the probe table ([[Probes]]): the ILA — a debug core the netlist debug flow creates on
  * the synthesized netlist, not an RTL instance.
  *
  * The RTL's half is the declarations: each keeps a wire named `probe_<name>` carrying `mark_debug`, which is what the
  * netlist debug flow preserves the net by. This half turns the declared entries into the three artifacts the flow
  * reads — the debug script the implementation run sources (`ila_debug.tcl`), the capture script the hardware manager
  * runs (`ila_capture.tcl`), and the rename map the capture flow applies (`ila_probes.map`) — and keeps the sampling
  * clock's own wire.
  *
  * One ILA per dump: the core's name and its depth are this consumer's, and its artifacts are single files, named after
  * what the flow hands the project rather than after the core.
  */
object Ila:

    /** The ILAs this dump opened, in construction order; cleared with the rest of a dump's state. */
    private val built = mutable.ArrayBuffer.empty[Ila]

    DumpRegistry.onReset(() => built.clear())

    /** The dump's ILA: what the artifacts are written from and what a check reads. */
    def table: Ila = built.headOption.getOrElse(
        throw new IllegalStateException("no ILA was opened in this elaboration")
    )

    /** Open an ILA for the debug script to create: `coreName` names the debug core and `depth` is the capture window (a
      * power of two — the core's `C_DATA_DEPTH`).
      */
    def apply(coreName: String, depth: Int): Ila =
        require(depth >= 2 && Integer.bitCount(depth) == 1, s"depth must be a power of two >= 2, got $depth")
        val ila = new Ila(coreName, depth)
        built += ila
        // Opening one is what says the dump has debug artifacts to write; the emitter holds this ILA, not the table.
        DumpRegistry.onEmit(s"ila:$coreName")(dir => ila.emit(dir))
        ila

    /** Write the table's artifacts into `dir`: the debug script (what the implementation run sources), the capture
      * script (the depth's trigger position baked in) and the rename map.
      */
    def emit(dir: os.Path): Unit = table.emit(dir)

/** One elaboration's ILA: [[Probes.table]] read as the debug flow's artifacts. It holds nothing but its own identity —
  * the probes are read from the table when the artifacts are, which is the dump's own window.
  */
final class Ila private (val coreName: String, val depth: Int):

    /** One probe: how the debug script resolves it, the netlist name pattern it resolves to, the name a waveform shows
      * it as, its width, and the keys the export's signal names are expected to contain ([[probeMap]]).
      */
    private case class Probe(resolve: Resolve, pattern: String, semantic: String, width: Int, netKeys: Seq[String])

    /** Where a probe's nets are searched for: a marked signal is looked up by leaf name through the whole hierarchy
      * (the declaration names the signal, not a place), a bound one at the instance's own level, by path.
      */
    private enum Resolve:
        case Leaf, Path

    private var clockNet: Option[String] = None

    /** The declared entries as probes, in binding order: a leaf entry resolves by its unique name (`*<net>*`), an
      * instance binding at the instance's own level (`*<instance>/<net>*`) and shows as `<instance>_<name>`.
      */
    private def probes: Seq[Probe] = Probes.table.map { entry =>
        val net = s"probe_${entry.name}"
        entry.place match
            case ProbePlace.Leaf =>
                Probe(Resolve.Leaf, s"*$net*", entry.name, entry.width, Seq(net))
            case ProbePlace.Instance(instance) =>
                val semantic = s"${instance}_${entry.name}"
                Probe(Resolve.Path, s"*$instance/$net*", semantic, entry.width, Seq(s"$instance/$net"))
    }

    /** Mark the clock the core samples on: keep a wire for it (`ila_clock`) and record it as the core's clock. A debug
      * core has one clock — the domain all its probes must belong to — and the kept wire is a net of that domain (the
      * buffer's output, once synthesis has placed the port's input buffer), which is what the debug script connects.
      */
    def clock[T <: Element](value: Referable[T])(using
        Arena,
        Context,
        Block,
        InstanceContext,
        sourcecode.File,
        sourcecode.Line
    ): Unit =
        require(clockNet.isEmpty, s"the ILA samples one clock; ${clockNet.get} is already kept")
        Probes.keepNet("ila_clock", value, Seq(VerilogAttribute("keep", None)))
        clockNet = Some("ila_clock")

    /** The name patterns the probes resolve to, in probe order — what the debug script searches for. */
    def patterns: Seq[String] = probes.map(_.pattern)

    /** Write this ILA's artifacts into `dir`: the debug script, the capture script and the rename map. */
    def emit(dir: os.Path): Unit =
        os.makeDir.all(dir)
        os.write.over(dir / "ila_debug.tcl", debugTcl)
        os.write.over(dir / "ila_capture.tcl", captureTcl)
        os.write.over(dir / "ila_probes.map", probeMap)

    /** The names the probes show as, in probe order. */
    def names: Seq[String] = probes.map(_.semantic)

    /** The probes' widths, in probe order. */
    def widths: Seq[Int] = probes.map(_.width)

    /** The debug script: what the implementation run sources before `opt_design` (`build_all.tcl` attaches it as
      * `STEPS.OPT_DESIGN.TCL.PRE` on `impl_1`). There the design is the synthesized netlist — `create_debug_core` only
      * exists after synthesis, and the declared wires are nets by name.
      *
      * Each probe's net is found by its pattern, in the mode its binding made: a marked signal by leaf name through the
      * hierarchy, a bound one at its instance's level by path. A search that does not come back with the number of nets
      * the probe was declared wide is an error, not a guess. A pure function, so a test can pin the text.
      */
    def debugTcl: String =
        val clock = clockNet.getOrElse(
            throw new IllegalStateException("the ILA's clock was never kept — call Ila.clock")
        )
        val probes = this.probes
        val header = Seq(
            "# The ILA debug core — generated by Ila.emit, do not edit.",
            "#",
            "# The implementation run sources this before opt_design: the design there is the synthesized netlist, the",
            "# wires the RTL declared `mark_debug` are nets by name, and create_debug_core — which only exists after",
            "# synthesis — builds the core the probes below are connected to. Implementation is the run's own business:",
            "# opt_design implements the core it finds in the netlist (and write_bitstream writes its .ltx beside the",
            "# bitstream, which is what the capture attaches).",
            "",
            "# A declared bus survives synthesis bit by bit, each named '<name>[i]': one probe is that bus, so the bits are",
            "# collected in index order.",
            "proc ila_bits {nets count pattern} {",
            "  if {[llength $nets] != $count} {",
            "    error \"ILA: '$pattern' matched [llength $nets] nets, expected $count: $nets\"",
            "  }",
            "  if {$count == 1} { return [lindex $nets 0] }",
            "  set indexed {}",
            "  foreach net $nets {",
            "    if {![regexp {\\[([0-9]+)\\]$} $net -> index]} { error \"ILA: '$net' is not a bit of a bus\" }",
            "    lappend indexed [list $index $net]",
            "  }",
            "  set ordered {}",
            "  foreach pair [lsort -integer -index 0 $indexed] { lappend ordered [lindex $pair 1] }",
            "  return $ordered",
            "}",
            "",
            "# A marked signal: the declaration names the signal, not a place. The design's own level is tried first — a",
            "# marked wire is the net where it was declared — and the rest is found through the hierarchy instead. A kept",
            "# net may be copied deeper by implementation (a copy inside the cell that drives it), so when the search finds",
            "# more than the declaration's own, the shallowest level is the declaration's and the copies are dropped. The",
            "# core's own nets are excluded (`NAME !~ <core>*`): connecting a probe makes the tool insert a net named after",
            "# it (`<core>_<net>`), and a declared name is a prefix of a longer one (`probe_locked` of `probe_lockedPlane`),",
            "# so a search that ran after the longer probe was connected would find the core's net for it as well — which",
            "# depends on the probe order, and is not what the declaration named. No net of the design carries the core's",
            "# name: the core does not exist in the RTL — the implementation run creates it.",
            "proc ila_shallow {nets} {",
            "  set min 0",
            "  foreach net $nets {",
            "    set depth [llength [split $net /]]",
            "    if {$min == 0 || $depth < $min} { set min $depth }",
            "  }",
            "  set shallow {}",
            "  foreach net $nets {",
            "    if {[llength [split $net /]] == $min} { lappend shallow $net }",
            "  }",
            "  return $shallow",
            "}",
            s"""proc ila_leaf {pattern count} {
               |  set nets [get_nets -quiet -filter "NAME !~ ${coreName}*" $$pattern]
               |  if {[llength $$nets] != $$count} {
               |    set nets [get_nets -hier -quiet -filter "NAME !~ ${coreName}*" $$pattern]
               |  }
               |  if {[llength $$nets] > $$count} { set nets [ila_shallow $$nets] }
               |  ila_bits $$nets $$count $$pattern
               |}""".stripMargin,
            "",
            "# A bound signal: the instance's own level in the netlist, addressed by its path from the design top. The path",
            "# is searched through the hierarchy — the instance sits wherever its parent put it — and by the name filter:",
            "# a path only matches as a name once the tool is asked for a filter, since `*` in a plain glob does not cross",
            "# a `/`. The shallowest matches are the instance's own, the way the leaf search takes the declaration's; the",
            "# core's own nets are excluded there for the same reason.",
            s"""proc ila_path {pattern count} {
               |  set nets [get_nets -hier -quiet -filter "NAME =~ $$pattern && NAME !~ ${coreName}*" ]
               |  if {[llength $$nets] > $$count} { set nets [ila_shallow $$nets] }
               |  ila_bits $$nets $$count $$pattern
               |}""".stripMargin,
            ""
        )
        // probe0 comes with the core; every further probe is one more probe port.
        val core =
            Seq(s"create_debug_core $coreName ila") ++
                Seq.fill(math.max(0, probes.size - 1))(s"create_debug_port $coreName probe") ++
                Seq(s"set_property C_DATA_DEPTH $depth [get_debug_cores $coreName]")
        val clockLines = Seq(
            s"set_property port_width 1 [get_debug_ports $coreName/clk]",
            s"connect_debug_port $coreName/clk [ila_leaf {*$clock*} 1]"
        )
        val probeLines = probes.zipWithIndex.flatMap { (probe, i) =>
            val resolve = if probe.resolve == Resolve.Leaf then "ila_leaf" else "ila_path"
            Seq(
                s"set_property port_width ${probe.width} [get_debug_ports $coreName/probe$i]",
                s"connect_debug_port $coreName/probe$i [$resolve {${probe.pattern}} ${probe.width}]"
            )
        }
        val footer = s"puts \"ILA: $coreName created with ${probes.size} probes, connected to the declared nets\""
        (header ++ core ++ Seq("") ++ clockLines ++ probeLines ++ Seq("", footer)).mkString("", "\n", "\n")

    /** The rename map (`probe vcd-rename`): `key name` per line, one key per net and one per probe port. The tool sorts
      * keys longest-first itself, so the order here is the map's, not the matcher's.
      *
      * A bound probe's key is the net's path spelling (what the export shows); a marked probe's key is its leaf name,
      * which is unique by construction.
      */
    def probeMap: String =
        val probes = this.probes
        val netLines = probes.flatMap(probe => probe.netKeys.map(key => s"$key ${probe.semantic}"))
        val portLines = probes.zipWithIndex.map((probe, i) => s"probe$i ${probe.semantic}")
        (netLines ++ portLines).mkString("", "\n", "\n")

    /** The capture script, with the depth-derived trigger position baked in. The second argument is the trigger: empty
      * (or `snapshot`) triggers immediately; `<probe>[=<value>]` arms on one probe's compare value — the probe named
      * the way the waveforms show it (resolved through the rename map beside this script), the value decimal or `0x…`
      * (default 1). Vivado 2023.2's compare values cannot express an edge and a sequence is not modelled, so one
      * probe's value is the whole trigger.
      */
    def captureTcl: String =
        s"""# ILA capture for the board top — generated by Ila.emit, do not edit.
           |#
           |# Usage: vivado -mode batch -source ila_capture.tcl -tclargs <part> [trigger]
           |#   part     the FPGA's part, e.g. xc7a200tfbg484-2
           |#   trigger  empty or `snapshot`: trigger immediately and capture one window
           |#            `<probe>`:            arm on <probe> == 1
           |#            `<probe>=<value>`:    arm on <probe> == <value> (decimal or 0x…)
           |#            (the probe is the semantic name — what the waveforms show)
           |#
           |# The capture lands in the working directory as capture.vcd; ila-capture.nu renames its probes.
           |set part [lindex $$argv 0]
           |set trigger [lindex $$argv 1]
           |set map [file join [file dirname [file normalize [info script]]] ila_probes.map]
           |
           |# A probe's compare value is set through the hardware manager object, so a semantic name must be resolved to
           |# the name the manager shows: the map's net keys are those names (a marked net as its leaf name, a bound one
           |# by path); its probeN keys are the VCD export's fallback, not manager names.
           |proc ila_keys {map semantic} {
           |  set f [open $$map r]
           |  set keys {}
           |  foreach line [split [read $$f] "\n"] {
           |    set line [string trim $$line]
           |    if {$$line eq ""} { continue }
           |    lassign [split $$line] key name
           |    if {$$name eq $$semantic && ![regexp {^probe[0-9]+$$} $$key]} { lappend keys $$key }
           |  }
           |  close $$f
           |  return $$keys
           |}
           |
           |# The semantic names a capture can arm on — what an unknown trigger is told to choose from.
           |proc ila_semantics {map} {
           |  set f [open $$map r]
           |  set names {}
           |  foreach line [split [read $$f] "\n"] {
           |    set line [string trim $$line]
           |    if {$$line eq ""} { continue }
           |    lassign [split $$line] key name
           |    if {![regexp {^probe[0-9]+$$} $$key] && [lsearch -exact $$names $$name] < 0} { lappend names $$name }
           |  }
           |  close $$f
           |  return $$names
           |}
           |
           |proc ila_probe {hw_ila map semantic} {
           |  set keys [ila_keys $$map $$semantic]
           |  if {[llength $$keys] == 0} {
           |    error "ILA: no probe '$$semantic' — this capture can arm on: [join [ila_semantics $$map] {, }]"
           |  }
           |  foreach probe [get_hw_probes -of_objects $$hw_ila] {
           |    if {[lsearch -exact $$keys [get_property NAME $$probe]] >= 0} { return $$probe }
           |  }
           |  foreach probe [get_hw_probes -of_objects $$hw_ila] {
           |    foreach key $$keys { if {[string match *$$key* [get_property NAME $$probe]]} { return $$probe } }
           |  }
           |  error "ILA: nothing in the hardware manager matches [join $$keys {, }]"
           |}
           |
           |open_hw_manager
           |connect_hw_server
           |open_hw_target
           |set hw_dev_found 0
           |foreach hw_dev [get_hw_devices] {
           |  if {[string first [get_property PART $$hw_dev] $$part] == 0} {
           |    current_hw_device $$hw_dev
           |    set hw_dev_found 1
           |    break
           |  }
           |}
           |if {!$$hw_dev_found} { error "no hw device of part $$part found — program the board first" }
           |# The probes file is generated by implementation beside the bitstream; without it the ILA core is invisible.
           |set ltx [glob -nocomplain [pwd]/nzea_fpga.runs/impl_1/*.ltx]
           |if {[llength $$ltx] > 0} { set_property PROBES.FILE [lindex $$ltx 0] [current_hw_device] }
           |refresh_hw_device [current_hw_device]
           |if {[llength [get_hw_ilas]] == 0} {
           |  error "no ILA core in the programmed device — rebuild and reprogram"
           |}
           |set hw_ila [lindex [get_hw_ilas] 0]
           |set_property CONTROL.TRIGGER_POSITION ${depth / 4} $$hw_ila
           |if {$$trigger eq "" || $$trigger eq "snapshot"} {
           |  run_hw_ila -trigger_now $$hw_ila
           |} else {
           |  lassign [split $$trigger "="] semantic raw
           |  set probe [ila_probe $$hw_ila $$map $$semantic]
           |  set width [get_property WIDTH $$probe]
           |  set value 1
           |  if {$$raw ne ""} { set value [expr $$raw] }
           |  if {$$value < 0 || $$value >= (1 << $$width)} {
           |    error "ILA: $$value does not fit '$$semantic' ($$width bits)"
           |  }
           |  set literal "eq$${width}'h[format %0*x [expr {(($$width + 3) / 4)}] $$value]"
           |  set_property TRIGGER_COMPARE_VALUE $$literal $$probe
           |  puts "ILA: armed on $$semantic == $$value ($$width bits)"
           |  run_hw_ila $$hw_ila
           |}
           |wait_on_hw_ila $$hw_ila
           |write_hw_ila_data -force -vcd_file capture.vcd [upload_hw_ila_data $$hw_ila]
           |
           |close_hw_target
           |close_hw_manager
           |puts "ILA capture written to [pwd]/capture.vcd"
           |""".stripMargin
