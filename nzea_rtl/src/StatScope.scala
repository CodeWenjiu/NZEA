package nzea_rtl

import java.lang.foreign.Arena
import scala.collection.mutable

import mainargs.TokensReader
import me.jiuyang.stdlib.default.given
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import nzea_rtl.stdlib.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** A declared counter's hardware: raise `en` with the count condition and `data` with its next value.
  *
  * The handle is what a module gets back from [[StatScope.stat]], and it is all a module needs: the name for a derived
  * rule, `inc`/`set` to drive it, and `value` to build the next value from.
  */
final class StatHandle private[nzea_rtl] (val counter: StatCounter, box: Instance[StatBoxIO, StatBoxProbe]):
    /** The counter's emitted name — what a derived rule references. */
    def name: String = counter.name

    /** The counter's current value, a cycle behind `inc`'s enable. */
    def value: Referable[UInt] = box.io.value

    /** Count one event per cycle `cond` is high. */
    def inc(cond: Referable[Bool])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        box.io.en := cond
        box.io.data := addWrap(box.io.value, 1.U(StatBox.Width), StatBox.Width)

    /** Load `data` when `cond` is high — a gauge that follows a value rather than a count. */
    def set(cond: Referable[Bool], data: Referable[UInt])(using
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): Unit =
        box.io.en := cond
        box.io.data := data

/** Where a block sits in the design, and the counters it declares.
  *
  * A module receives its scope in its own parameter, built by the parent that instantiates it (`MiddleParameter(…,
  * stats = core.stats.child(Middle.Source))`), so the ownership a schema reports mirrors the instantiation that
  * produced it, and the relation is written once — where the hardware relation is decided anyway. Declaring a counter
  * is one call:
  *
  * {{{
  *   val commits = stats.stat("inst_commit", Regions.Throughput, "retired instructions")
  *   commits.inc(done)
  * }}}
  *
  * The call composes the counter's name from the scope and the local name, registers it, instantiates its box and
  * returns the handle that drives it. Nothing else has to know the counter exists: the dump reads the registered set
  * back out of [[StatRegistry]] after elaboration and emits the boxes and the schema from it.
  */
final case class StatScope(path: Seq[String]):

    /** The scope of a block inside this one: the segment is the block's own name. */
    def child(segment: String): StatScope = StatScope(path :+ segment)

    /** Declare a counter, instantiate its box where the caller stands, and hand back the handle that drives it. */
    def stat(local: String, region: StatRegion, doc: String, width: Int = StatBox.Width)(using
        ClockScope,
        ResetScope,
        Arena,
        Context,
        Block,
        sourcecode.File,
        sourcecode.Line,
        sourcecode.Name.Machine,
        InstanceContext
    ): StatHandle =
        require(path.nonEmpty, s"stat '$local' is declared from a block, which has to have a name")
        val name = s"${StatSchema.CounterPrefix}${(path :+ local).mkString("_")}"
        val counter = StatCounter(name, width, path.last, region, doc)
        val box = StatBoxModule.instantiate(StatBoxParameter(counter.name, counter.width))
        box.io.clock := summon[ClockScope].clock
        box.io.resetN := summon[ResetScope].reset.asBool
        StatRegistry.declare(counter)
        new StatHandle(counter, box)

    /** Declare a derived statistic: an expression over counters and rules declared before it. */
    def rule(name: String, expr: String, region: StatRegion, unit: String, digits: Int, doc: String): Unit =
        require(path.nonEmpty, s"rule '$name' is declared from a block, which has to have a name")
        StatRegistry.declare(StatDerived(name, expr, path.last, region, unit, digits, doc))

object StatScope:

    /** The design's root: what the dump entry point hands the top module. */
    val root: StatScope = StatScope(Seq.empty)

    given upickle.default.ReadWriter[StatScope] = upickle.default
        .readwriter[Seq[String]]
        .bimap(_.path, StatScope(_))

    /** The flag's spelling: the path segments joined by dots, or `root` for the design's own scope. */
    given TokensReader.Simple[StatScope] with
        def shortName = "root|block(.block)*"

        def read(strs: Seq[String]): Either[String, StatScope] =
            strs.lastOption match
                case None         => Left("stats requires a value")
                case Some("root") => Right(root)
                case Some(token)  => Right(StatScope(token.split("\\.").toSeq.filter(_.nonEmpty)))

/** The counters and rules one elaboration declared, in declaration order.
  *
  * A dump reads this back after elaborating the design (the generator runs in the same process), which is what lets a
  * module declare a counter at the call site and still have every counter box and the schema emitted from one place.
  * [[Elaborate.withLinked]] resets it, so every lowering starts from an empty set.
  */
object StatRegistry:

    private val counters = mutable.LinkedHashMap.empty[String, StatCounter]
    private val rules = mutable.ArrayBuffer.empty[StatDerived]

    def reset(): Unit =
        counters.clear()
        rules.clear()

    private[nzea_rtl] def declare(counter: StatCounter): Unit =
        require(
            !counters.contains(counter.name),
            s"counter '${counter.name}' is declared twice (names are the VPI leaf names, so they have to be unique)"
        )
        counters.put(counter.name, counter)

    private[nzea_rtl] def declare(rule: StatDerived): Unit = rules += rule

    def declaredCounters: Seq[StatCounter] = counters.values.toSeq

    def declaredRules: Seq[StatDerived] = rules.toSeq

    /** The regions the declarations use, first use first. */
    def declaredRegions: Seq[StatRegion] =
        (declaredCounters.map(_.region) ++ declaredRules.map(_.region)).distinctBy(_.name)

    /** What a dump carries: everything the elaboration declared, in the order the blocks declared it. */
    def schema: StatSchema = StatSchema(declaredRegions, declaredCounters, declaredRules)

    /** The module name the box of `counter` is emitted under (and instantiated as). */
    def boxModuleName(counter: StatCounter): String =
        StatBoxModule.moduleName(StatBoxParameter(counter.name, counter.width))

    /** Write one SystemVerilog file per declared counter into `dir` and add them to its file list, which is the list
      * the dump's lowering adds to. Returns the file names written.
      */
    def emitBoxes(dir: os.Path): Seq[String] =
        os.makeDir.all(dir)
        val names = declaredCounters.map { counter =>
            val moduleName = boxModuleName(counter)
            val file = s"$moduleName.sv"
            os.write.over(dir / file, StatBox.verilog(moduleName, counter))
            file
        }
        FileList.append(dir, names)
        names
