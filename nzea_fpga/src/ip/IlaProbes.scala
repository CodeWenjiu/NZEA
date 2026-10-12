package nzea_fpga.ip

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.DumpRegistry
import org.llvm.mlir.scalalib.capi.ir.{given_AttributeApi, given_OperationApi, Block, Context}

import scala.collection.mutable

/** The ILA's probe table: what the design watches, stated by declaring it where the signal lives and binding it where
  * the instance is known.
  *
  * A signal is *declared* beside itself — [[IlaProbes.declare]] keeps a wire named `ila_<name>` for it and declares it
  * `mark_debug` (the attribute Vivado's netlist debug flow preserves nets by) — and a *binding* ([[watch]]) turns a
  * declaration into a probe of one instance: the debug script resolves that instance's net by name in the synthesized
  * netlist, so nothing has to be threaded up the hierarchy, which is what lets a declaration sit at any depth.
  *
  * The two statements exist because a module's body is shared by every instance of it: the body can state that a signal
  * is watchable, but not *which* instance a probe should mean — that is stated where the instance is named. [[mark]] is
  * the one-line form of both, for a signal whose instance is known where it lives (the top's own signals, a module
  * instantiated once).
  *
  * Names are one namespace: a declaration's name is what a binding searches for, and the search must come back with the
  * number of nets the declaration's width says — so a name must be unique in the design, and a binding is per instance.
  *
  * One ILA per dump: the core's name and its depth belong to the table, and its artifacts are single files, named after
  * what the flow hands the project rather than after the core.
  */
object IlaProbes:

    /** The tables this dump built, in construction order; cleared with the rest of a dump's state. */
    private val built = mutable.ArrayBuffer.empty[IlaProbes]

    DumpRegistry.onReset(() => built.clear())

    /** The dump's probe table: what the artifacts are written from and what a check reads. */
    def table: IlaProbes = built.headOption.getOrElse(
        throw new IllegalStateException("no ILA probes were marked in this elaboration")
    )

    /** Open a table for the ILA the debug script creates: `coreName` names the debug core and `depth` is the capture
      * window (a power of two — the core's `C_DATA_DEPTH`).
      */
    def apply(coreName: String, depth: Int): IlaProbes =
        require(depth >= 2 && Integer.bitCount(depth) == 1, s"depth must be a power of two >= 2, got $depth")
        val probes = new IlaProbes(coreName, depth)
        built += probes
        probes

    /** Declare `value` watchable, from where the signal lives: keep a wire named `ila_<name>` for it, declare it
      * `mark_debug`, and record the declaration for a binding to name.
      *
      * The wire is what synthesis preserves the net as, and its width is the value's own. `name` is the one fact the
      * caller states; a design with no ILA has nothing to record for and only carries the wire.
      */
    def declare[T <: Element](name: String)(value: Referable[T])(using
        arena: Arena,
        context: Context,
        block: Block,
        typeImpl: TypeImpl,
        instanceContext: InstanceContext,
        file: sourcecode.File,
        line: sourcecode.Line
    ): Unit =
        keep(s"ila_$name", value, Seq(VerilogAttribute("mark_debug", Some("\"true\""))))
        val width = value.width(using arena, context, typeImpl)
        built.foreach(_.recordDeclaration(name, width))

    /** Write the table's artifacts into `dir`: the debug script (what the implementation run sources), the capture
      * script (the depth's trigger position baked in) and the rename map.
      */
    def emit(dir: os.Path): Unit = table.emit(dir)

    /** The kept wire and its name: the one statement [[declare]] and [[clock]] both make. `NodeWithAttributes` names by
      * `sourcecode.Name.Machine`, so the wire is named by the caller rather than after whatever binds the call: the
      * name is part of the interface (the debug script's search, the rename map's key), not an accident of the call
      * site. It also holds the value down (the dont-touch annotation): an attribute rides a declaration, and a value
      * the compiler folds into its user leaves the attribute nowhere to go.
      */
    private def keep[T <: Element](net: String, value: Referable[T], attributes: Seq[VerilogAttribute])(using
        arena: Arena,
        context: Context,
        block: Block,
        file: sourcecode.File,
        line: sourcecode.Line,
        instanceContext: InstanceContext
    ): Unit =
        val _ = NodeWithAttributes(value, attributes)(using
            arena,
            context,
            block,
            file,
            line,
            sourcecode.Name.Machine(net),
            instanceContext
        )

/** One elaboration's probe table, built by [[IlaProbes.apply]] and filled in by [[IlaProbes.declare]] and [[watch]]. */
final class IlaProbes private (val coreName: String, val depth: Int):

    /** One probe: how the debug script resolves it, the netlist name pattern it resolves to, the name a waveform shows
      * it as, its width, and the keys the export's signal names are expected to contain ([[probeMap]]).
      */
    private case class Probe(resolve: Resolve, pattern: String, semantic: String, width: Int, netKeys: Seq[String])

    /** Where a probe's nets are searched for: a marked signal is looked up by leaf name through the whole hierarchy
      * (the declaration names the signal, not a place), a bound one at the instance's own level, by path.
      */
    private enum Resolve:
        case Leaf, Path

    private val probes = mutable.ArrayBuffer.empty[Probe]

    private var clockNet: Option[String] = None

    /** The signals the design declared watchable, by name: what a binding looks up. */
    private val declared = mutable.LinkedHashMap.empty[String, Int]

    private def recordDeclaration(name: String, width: Int): Unit =
        require(
            !declared.contains(name),
            s"'$name' is declared watchable twice — a declaration's name is what a binding searches for, so it names one signal"
        )
        declared(name) = width

    /** Mark `value` watched: declare it and bind it here — the one-line form for a signal whose instance is known where
      * it lives (the top's own signals, a module instantiated once). Otherwise declare it at the signal
      * ([[IlaProbes.declare]]) and bind it ([[watch]]) where the instance is named.
      */
    def mark[T <: Element](name: String)(value: Referable[T])(using
        arena: Arena,
        context: Context,
        block: Block,
        typeImpl: TypeImpl,
        instanceContext: InstanceContext,
        file: sourcecode.File,
        line: sourcecode.Line
    ): Unit =
        IlaProbes.declare(name)(value)
        probes += Probe(Resolve.Leaf, s"*ila_$name*", name, declared(name), Seq(s"ila_$name"))

    /** Bind the signal an instance declares: one probe of that instance, named `<instance>_<name>`. The instance's own
      * name is read from its op — the binding repeats no string the instantiation already made.
      *
      * A synthesized net carries its hierarchy path around the leaf, so the probe addresses the instance's own level
      * (`*<instance>/<leaf>*`) wherever the instance sits, and a shared body's other instances are simply other levels;
      * the width check tells a wrong instance or a missing declaration apart from a match that is not the one declared.
      */
    def watch(instance: Instance[?, ?], name: String)(using
        arena: Arena,
        context: Context,
        typeImpl: TypeImpl
    ): Unit =
        val width = declared.getOrElse(
            name,
            throw new IllegalArgumentException(
                s"no declaration named '$name' — declare it at the signal (`IlaProbes.declare`) first"
            )
        )
        val instanceName =
            instance.operation(using typeImpl).getInherentAttributeByName("name").stringAttrGetValue(using arena)
        val leaf = s"ila_$name"
        probes += Probe(
            Resolve.Path,
            s"*${instanceName}/$leaf*",
            s"${instanceName}_$name",
            width,
            Seq(s"$instanceName/$leaf")
        )

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
        IlaProbes.keep("ila_clock", value, Seq(VerilogAttribute("keep", None)))
        clockNet = Some("ila_clock")

    /** The name patterns the probes resolve to, in probe order — what the debug script searches for. */
    def patterns: Seq[String] = probes.map(_.pattern).toSeq

    /** Write this table's artifacts into `dir`: the debug script, the capture script and the rename map. */
    def emit(dir: os.Path): Unit =
        os.makeDir.all(dir)
        os.write.over(dir / "ila_debug.tcl", debugTcl)
        os.write.over(dir / "ila_capture.tcl", captureTcl)
        os.write.over(dir / "ila_probes.map", probeMap)

    /** The names the probes show as, in probe order. */
    def names: Seq[String] = probes.map(_.semantic).toSeq

    /** The probes' widths, in probe order. */
    def widths: Seq[Int] = probes.map(_.width).toSeq

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
            throw new IllegalStateException("the ILA's clock was never kept — call IlaProbes.clock")
        )
        val header = Seq(
            "# The ILA debug core — generated by IlaProbes.emit, do not edit.",
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
            "# marked wire is the net where it was declared — and a wire synthesis kept inside the cell that drives it is",
            "# found through the hierarchy instead.",
            "proc ila_leaf {pattern count} {",
            "  set nets [get_nets -quiet $pattern]",
            "  if {[llength $nets] != $count} { set nets [get_nets -hier -quiet $pattern] }",
            "  ila_bits $nets $count $pattern",
            "}",
            "",
            "# A bound signal: the instance's own level in the netlist, addressed by its path from the design top.",
            "proc ila_path {pattern count} { ila_bits [get_nets -quiet $pattern] $count $pattern }",
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
        val netLines = probes.flatMap(probe => probe.netKeys.map(key => s"$key ${probe.semantic}"))
        val portLines = probes.zipWithIndex.map((probe, i) => s"probe$i ${probe.semantic}")
        (netLines ++ portLines).mkString("", "\n", "\n")

    /** The capture script, with the depth-derived trigger position baked in. */
    def captureTcl: String =
        s"""# ILA capture for the board top — generated by IlaProbes.emit, do not edit.
           |#
           |# Usage: vivado -mode batch -source ila_capture.tcl -tclargs <part> [mode]
           |#   part  the FPGA's part, e.g. xc7a200tfbg484-2
           |#   mode  snapshot (default): trigger immediately and capture one window
           |#
           |# The capture lands in the working directory as capture.vcd; ila-capture.nu renames its probes.
           |set part [lindex $$argv 0]
           |set mode [lindex $$argv 1]
           |if {$$mode eq ""} { set mode "snapshot" }
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
           |run_hw_ila -trigger_now $$hw_ila
           |wait_on_hw_ila $$hw_ila
           |write_hw_ila_data -force -vcd_file capture.vcd [upload_hw_ila_data $$hw_ila]
           |
           |close_hw_target
           |close_hw_manager
           |puts "ILA capture written to [pwd]/capture.vcd"
           |""".stripMargin
