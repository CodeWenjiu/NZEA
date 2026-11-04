package nzea_rtl.probe

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.DumpRegistry
import org.llvm.mlir.scalalib.capi.ir.{given_AttributeApi, given_OperationApi, Block, Context}

import scala.collection.mutable

/** How a probe's net is addressed: by the declaration alone, or at one instance of the declaring module. */
enum ProbePlace:
    /** The declaration is the address — the name is unique in the design, so a consumer searches for the marked net by
      * leaf name through the hierarchy, wherever it ended up.
      */
    case Leaf

    /** One instance of the declaring module: the net sits under the instance's path in the netlist. */
    case Instance(instance: String)

/** One thing a design watches: the name a waveform shows, the width the signal declares, and how its net is addressed.
  *
  * The entry stays raw — a leaf name or an instance path — not a tool's query syntax: what a net search looks like is
  * the consumer's business, and each consumer turns these into whatever its tool wants.
  */
final case class ProbeEntry(name: String, width: Int, place: ProbePlace)

/** The probes one dump declares: a module states beside its signal that the signal is watchable ([[declare]]), and the
  * place that names the instance binds the declaration to it ([[watch]], or [[mark]] in one step where the instance is
  * the declaring module itself).
  *
  * What the dump keeps is a selection: [[select]] names the probes (globs, matched whole), and a declaration outside it
  * creates neither a wire nor a table entry — a build that asked for no probe carries no probe logic at all. A dump
  * that never selects keeps everything, so a bare module's dump carries what its body declares; a level that has a
  * selection states it before its design elaborates (the board's top takes its own from its parameter).
  *
  * A kept declaration holds a wire named `probe_<name>` carrying `mark_debug` — the attribute Vivado's netlist debug
  * flow preserves nets by — and records the entry here. Whether anything reads the table is the dump level's choice (a
  * consumer, like the fpga level's ILA); a design whose level has none simply carries the wires. The table lives one
  * elaboration: [[DumpRegistry]] clears it with the rest of a dump's state.
  *
  * Names are one namespace: a declaration's name is what a binding looks up, so it must be unique in the design, and it
  * must not contain another declaration's — the leaf search that addresses a declared net is a substring match. A
  * binding is per instance.
  */
object Probes:

    /** What this dump keeps: names and globs, matched whole, `*` matching any run. The reset default is every name, so
      * a level that states nothing loses nothing; a level with a selection (`select`) overrides it.
      */
    private var patterns: Seq[String] = Seq("*")

    /** The dump's declarations, by name: what a binding looks up, and with what width. */
    private val declared = mutable.LinkedHashMap.empty[String, Int]

    /** The dump's bindings, in order: a declaration bound once per probe — one probe per instance for a shared body. */
    private val bindings = mutable.ArrayBuffer.empty[ProbeEntry]

    DumpRegistry.onReset { () =>
        declared.clear()
        bindings.clear()
        patterns = Seq("*")
    }

    /** Keep only the probes whose declared name matches one of `patterns` (globs, matched whole; `*` matches any run).
      * A level states this before its design elaborates; an empty list keeps none, which is the board's default.
      */
    def select(patterns: Seq[String]): Unit =
        this.patterns = patterns.map(_.trim).filter(_.nonEmpty)

    /** Whether this dump keeps any probe at all. */
    def selected: Boolean = patterns.nonEmpty

    /** Whether the dump keeps the declaration named `name`. */
    def watching(name: String): Boolean =
        patterns.exists(pattern =>
            name.matches(pattern.split("\\*", -1).map(java.util.regex.Pattern.quote).mkString(".*"))
        )

    /** Declare `value` watchable, from where the signal lives: keep a wire named `probe_<name>` for it, mark it for the
      * netlist debug flow, and record the declaration for a binding to name. The wire's width is the value's own.
      *
      * `name` is the one fact the caller states. Declaring one name twice is an error: it must address one signal.
      */
    def declare[T <: Element](name: String)(value: => Referable[T])(using
        arena: Arena,
        context: Context,
        block: Block,
        typeImpl: TypeImpl,
        instanceContext: InstanceContext,
        file: sourcecode.File,
        line: sourcecode.Line
    ): Unit =
        if watching(name) then
            val kept = value
            require(
                !declared.contains(name),
                s"'$name' is declared watchable twice — a declaration's name is what a binding searches for, so it names one signal"
            )
            // A leaf-placed declaration is found by searching the hierarchy for a net named after it, and the search is
            // a substring match on the leaf name: a name that contains another (or is contained by it) would be found by
            // both searches, and the count check in the consumer would fail — a build later, or a reading mistake now.
            // Names must be distinguishable by the search that addresses them.
            declared.keysIterator.find(other => other.contains(name) || name.contains(other)).foreach { other =>
                require(
                    false,
                    s"'$name' and '$other' cannot both name a declared net: each is searched for by leaf name, and one name " +
                        s"contains the other, so the search for '*probe_$other*' would find 'probe_$name' as well"
                )
            }
            keepNet(s"probe_$name", kept, Seq(VerilogAttribute("mark_debug", Some("\"true\""))))
            declared(name) = kept.width(using arena, context, typeImpl)

    /** Mark `value` watched: declare it and bind it here — the one-line form for a signal whose instance is known where
      * it lives (a level top's own signals, a module instantiated once). Otherwise declare it at the signal
      * ([[declare]]) and bind it ([[watch]]) where the instance is named.
      */
    def mark[T <: Element](name: String)(value: => Referable[T])(using
        arena: Arena,
        context: Context,
        block: Block,
        typeImpl: TypeImpl,
        instanceContext: InstanceContext,
        file: sourcecode.File,
        line: sourcecode.Line
    ): Unit =
        if watching(name) then
            declare(name)(value)
            bindings += ProbeEntry(name, declared(name), ProbePlace.Leaf)

    /** Bind the signal a module declares: one probe of that instance. The instance's own name is read from its op — the
      * binding repeats no string the instantiation already made — and a synthesized net carries its hierarchy path
      * around the leaf, so a consumer addresses the instance's own level wherever the instance sits.
      */
    def watch(instance: Instance[?, ?], name: String)(using arena: Arena, context: Context, typeImpl: TypeImpl): Unit =
        if watching(name) then
            val width = declared.getOrElse(
                name,
                throw new IllegalArgumentException(
                    s"no declaration named '$name' — declare it at the signal (`Probes.declare`) first"
                )
            )
            val instanceName =
                instance.operation(using typeImpl).getInherentAttributeByName("name").stringAttrGetValue(using arena)
            bindings += ProbeEntry(name, width, ProbePlace.Instance(instanceName))

    /** The dump's bindings, in probe order — what a consumer turns into its tool's own wiring. */
    def table: Seq[ProbeEntry] = bindings.toSeq

    /** Keep `value` as a net named `net`, carrying `attributes`: the one statement [[declare]] and a consumer's own
      * kept anchors (the ILA's sampling clock) both make.
      *
      * `NodeWithAttributes` names by `sourcecode.Name.Machine`, so the wire is named by the caller rather than after
      * whatever binds the call: the name is part of the interface (a net search's key), not an accident of the call
      * site. It also holds the value down (the dont-touch annotation): an attribute rides a declaration, and a value
      * the compiler folds into its user leaves the attribute nowhere to go.
      */
    def keepNet[T <: Element](net: String, value: Referable[T], attributes: Seq[VerilogAttribute])(using
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
