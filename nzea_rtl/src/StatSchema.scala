package nzea_rtl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Classification assigned by the RTL design to counters and derived entries (`bp`, `throughput`, ...). */
final case class StatRegion(name: String, doc: String)

/** One counter exposed by the RTL: `name` is the VPI leaf name of the register, `width` its bit width. */
final case class StatCounter(name: String, width: Int, region: StatRegion, doc: String)

/** One derived statistic: an expression over counters and derived entries declared earlier (see [[StatSchema]]). */
final case class StatDerived(name: String, expr: String, region: StatRegion, unit: String, digits: Int, doc: String)

/** Machine-readable contract a simulator reads to interpret an RTL design's `stat_*` counters.
  *
  * One file is emitted per simulation dump (`<design>.stats.toml`, see [[StatSchema.emit]]). Rules:
  *   - `name` is the VPI leaf name; counter names are globally unique and use the `stat_` prefix.
  *   - derived entries are evaluated in file order: a rule may reference counters and rules declared above it, so
  *     dependency cycles cannot exist and the consumer needs no graph resolution.
  *   - when a reference is unavailable or a division has a zero denominator, the entry is reported as unavailable
  *     (`n/a`) — it never renders as a fabricated number.
  *
  * [[validate]] is the normative check; [[evaluate]] is the reference semantics used by tests.
  */
final case class StatSchema(regions: Seq[StatRegion], counters: Seq[StatCounter], derived: Seq[StatDerived]):

  private def fail(msg: String): Nothing = throw new IllegalArgumentException(s"invalid stat schema: $msg")

  /** Full schema check: uniqueness, region membership, expression syntax and in-order reference resolution. */
  def validate(): Unit =
    regions.foreach(r => if r.name.trim.isEmpty then fail("empty region name"))
    regions
      .map(_.name)
      .diff(regions.map(_.name).distinct)
      .headOption
      .foreach(n => fail(s"duplicate region '$n'"))

    counters.foreach { c =>
      if !c.name.startsWith(StatSchema.CounterPrefix) then
        fail(s"counter '${c.name}' must use the ${StatSchema.CounterPrefix} prefix")
      if c.width <= 0 || c.width > StatSchema.MaxCounterWidth then
        fail(s"counter '${c.name}' width must be in 1..${StatSchema.MaxCounterWidth}")
      if !regions.exists(_.name == c.region.name) then
        fail(s"counter '${c.name}' references unknown region '${c.region.name}'")
    }
    counters
      .map(_.name)
      .diff(counters.map(_.name).distinct)
      .headOption
      .foreach(n => fail(s"duplicate counter '$n'"))

    derived.foreach { d =>
      if !regions.exists(_.name == d.region.name) then
        fail(s"derived '${d.name}' references unknown region '${d.region.name}'")
      if d.digits < 0 || d.digits > StatSchema.MaxDigits then
        fail(s"derived '${d.name}' digits must be in 0..${StatSchema.MaxDigits}")
      if counters.exists(_.name == d.name) then fail(s"derived '${d.name}' shadows a counter")
    }
    derived
      .map(_.name)
      .diff(derived.map(_.name).distinct)
      .headOption
      .foreach(n => fail(s"duplicate derived '$n'"))

    val known = scala.collection.mutable.LinkedHashSet.from(counters.map(_.name))
    derived.foreach { d =>
      val node =
        try StatExpr.parse(d.expr)
        catch case e: IllegalArgumentException => fail(s"derived '${d.name}': ${e.getMessage}")
      StatExpr.refs(node).foreach { ref =>
        if !known.contains(ref) then
          fail(s"derived '${d.name}' references '$ref', which is neither a counter nor an earlier derived entry")
      }
      known += d.name
    }

  /** Reference semantics: counters come from `values` (absent counter = unavailable), derived entries in file order. */
  def evaluate(values: Map[String, Long]): Map[String, Option[Double]] =
    var acc: Map[String, Option[Double]] = values.map { case (name, v) => name -> Some(v.toDouble) }
    derived.foreach { d =>
      val node = StatExpr.parse(d.expr)
      acc = acc.updated(d.name, StatExpr.eval(node, ref => acc.get(ref).flatten))
    }
    acc

  /** Serialize as TOML, declaration order preserved (it is the display and evaluation order). */
  def toToml(design: String): String =
    val sb = new StringBuilder
    sb ++= s"schema = ${StatSchema.Version}\n"
    sb ++= s"""design = "${StatSchema.escape(design)}"\n"""
    regions.foreach { r =>
      sb ++= "\n[[region]]\n"
      sb ++= s"""name = "${StatSchema.escape(r.name)}"\n"""
      sb ++= s"""doc = "${StatSchema.escape(r.doc)}"\n"""
    }
    counters.foreach { c =>
      sb ++= "\n[[counter]]\n"
      sb ++= s"""name = "${StatSchema.escape(c.name)}"\n"""
      sb ++= s"width = ${c.width}\n"
      sb ++= s"""region = "${StatSchema.escape(c.region.name)}"\n"""
      sb ++= s"""doc = "${StatSchema.escape(c.doc)}"\n"""
    }
    derived.foreach { d =>
      sb ++= "\n[[derived]]\n"
      sb ++= s"""name = "${StatSchema.escape(d.name)}"\n"""
      sb ++= s"""expr = "${StatSchema.escape(d.expr)}"\n"""
      sb ++= s"""region = "${StatSchema.escape(d.region.name)}"\n"""
      sb ++= s"""unit = "${StatSchema.escape(d.unit)}"\n"""
      sb ++= s"digits = ${d.digits}\n"
      sb ++= s"""doc = "${StatSchema.escape(d.doc)}"\n"""
    }
    sb.toString

object StatSchema:

  val Version: Int = 1

  /** VPI leaf-name prefix every counter shares (uniqueness contract across all [[StatsRegs]] instances). */
  val CounterPrefix: String = "stat_"

  /** Consumer limits of schema v1: a 64-bit VPI readout and f64 display precision. */
  val MaxCounterWidth: Int = 64
  val MaxDigits: Int = 30

  /** Suffix of the emitted schema file. */
  val FileSuffix: String = ".stats.toml"

  private def escape(s: String): String =
    s.flatMap {
      case '\\'         => "\\\\"
      case '"'          => "\\\""
      case '\n'         => "\\n"
      case '\t'         => "\\t"
      case '\r'         => "\\r"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    }

  /** Validate `schema` and write `<dir>/<design>[[FileSuffix]]`; returns the written path. */
  def emit(dir: String, design: String, schema: StatSchema): String =
    schema.validate()
    val path = Paths.get(dir, s"$design$FileSuffix")
    Files.createDirectories(path.getParent)
    Files.write(path, schema.toToml(design).getBytes(StandardCharsets.UTF_8))
    println(s"Stats schema: $path")
    path.toString
