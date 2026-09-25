package nzea_rtl

import java.nio.file.Files
import org.scalatest.freespec.AnyFreeSpec

/** Schema contract tests: expression grammar, in-order rule resolution, TOML emission. */
class StatSchemaTest extends AnyFreeSpec {

  private val Region = StatRegion("g", "group")
  private val a = StatCounter("stat_a", 8, Region, "counter a")
  private val b = StatCounter("stat_b", 8, Region, "counter b")

  private def schema(derived: Seq[StatDerived], counters: Seq[StatCounter] = Seq(a, b)) =
    StatSchema(Seq(Region), counters, derived)

  private def derive(name: String, expr: String) = StatDerived(name, expr, Region, "", 4, "")

  private val values = Map("stat_a" -> 5L, "stat_b" -> 2L)

  "expression grammar" - {

    "precedence, unary minus, parentheses and functions" in {
      val v = Map("x" -> 3.0)
      def ev(expr: String) = StatExpr.eval(StatExpr.parse(expr), v.get(_))
      assert(ev("1 + 2 * 3") == Some(7.0))
      assert(ev("(1 + 2) * 3") == Some(9.0))
      assert(ev("-x + 1") == Some(-2.0))
      assert(ev("2 * -x") == Some(-6.0))
      assert(ev("max(2, x)") == Some(3.0))
      assert(ev("min(2, x)") == Some(2.0))
      assert(ev("abs(0 - x)") == Some(3.0))
      assert(ev("floor(x / 2)") == Some(1.0))
    }

    "rejects malformed input" in {
      def rejected(expr: String): Boolean =
        try { StatExpr.parse(expr); false }
        catch { case _: IllegalArgumentException => true }
      assert(rejected("1 +"))
      assert(rejected("(1 + 2"))
      assert(rejected("1 2"))
      assert(rejected("bogus(1)"))
      assert(rejected("max(1)"))
      assert(rejected(""))
    }

    "unavailable instead of fabricated numbers" in {
      val node = StatExpr.parse("stat_a / stat_b")
      assert(StatExpr.eval(node, Map("stat_a" -> 5.0, "stat_b" -> 2.0).get(_)) == Some(2.5))
      assert(StatExpr.eval(node, Map("stat_a" -> 5.0, "stat_b" -> 0.0).get(_)).isEmpty)
      assert(StatExpr.eval(node, Map("stat_a" -> 5.0).get(_)).isEmpty)
    }

    "collects references" in {
      assert(StatExpr.refs(StatExpr.parse("stat_a + max(stat_b, 2)")) == Set("stat_a", "stat_b"))
    }
  }

  "schema validation" - {

    "accepts a well-formed in-order schema" in {
      schema(Seq(derive("ratio", "stat_a / stat_b"), derive("inverse", "1 / ratio"))).validate()
    }

    "rejects a reference that is not a counter or an earlier rule" in {
      val e = intercept[IllegalArgumentException] {
        schema(Seq(derive("bad", "stat_missing + 1"))).validate()
      }
      assert(e.getMessage.contains("stat_missing"), e.getMessage)
    }

    "rejects a forward reference (cycle by construction)" in {
      val e = intercept[IllegalArgumentException] {
        schema(Seq(derive("cpi", "1 / ipc"), derive("ipc", "stat_a / stat_b"))).validate()
      }
      assert(e.getMessage.contains("ipc"), e.getMessage)
    }

    "rejects duplicates, unknown regions and shadowing" in {
      intercept[IllegalArgumentException] { schema(Seq(derive("r", "1")), Seq(a, a)).validate() }
      intercept[IllegalArgumentException] {
        schema(Seq(StatDerived("r", "1", StatRegion("nope", ""), "", 4, ""))).validate()
      }
      intercept[IllegalArgumentException] { schema(Seq(derive("stat_a", "1"))).validate() }
      intercept[IllegalArgumentException] { schema(Seq(derive("r", "stat_a +"), derive("r", "1"))).validate() }
      intercept[IllegalArgumentException] {
        StatSchema(Seq(Region), Seq(StatCounter("cycle", 8, Region, "")), Seq.empty).validate()
      }
    }

    "enforces the consumer limits of schema v1" in {
      val wide = StatCounter("stat_w", StatSchema.MaxCounterWidth, Region, "")
      val tooWide = StatCounter("stat_w", StatSchema.MaxCounterWidth + 1, Region, "")
      schema(Seq.empty, Seq(wide)).validate()
      schema(Seq(StatDerived("r", "1", Region, "", StatSchema.MaxDigits, ""))).validate()
      intercept[IllegalArgumentException] { schema(Seq.empty, Seq(tooWide)).validate() }
      intercept[IllegalArgumentException] {
        schema(Seq(StatDerived("r", "1", Region, "", StatSchema.MaxDigits + 1, ""))).validate()
      }
    }
  }

  "evaluation" - {

    "derives in file order and propagates unavailability" in {
      val s = schema(Seq(derive("ipc", "stat_a / stat_b"), derive("cpi", "1 / ipc")))
      val out = s.evaluate(values)
      assert(out("ipc") == Some(2.5))
      assert(out("cpi") == Some(0.4))

      val divZero = s.evaluate(Map("stat_a" -> 5L, "stat_b" -> 0L))
      assert(divZero("ipc").isEmpty)
      assert(divZero("cpi").isEmpty)
    }
  }

  "toml emission" - {

    "contains every declaration with its region" in {
      val toml = schema(Seq(derive("ratio", "stat_a / stat_b"))).toToml("NzeaCore")
      assert(toml.startsWith("schema = 1\n"), toml)
      assert(toml.contains("""design = "NzeaCore""""), toml)
      assert(toml.contains("[[region]]\nname = \"g\"\ndoc = \"group\"\n"), toml)
      assert(toml.contains("[[counter]]\nname = \"stat_a\"\nwidth = 8\nregion = \"g\"\n"), toml)
      assert(toml.contains("[[derived]]\nname = \"ratio\"\nexpr = \"stat_a / stat_b\"\nregion = \"g\"\n"), toml)
      assert(toml.contains("digits = 4\n"), toml)
    }

    "escapes quotes and backslashes" in {
      val toml = StatSchema(Seq(StatRegion("g", "a \"quoted\" doc")), Seq.empty, Seq.empty).toToml("NzeaCore")
      assert(toml.contains("""doc = "a \"quoted\" doc""""), toml)
    }

    "emit writes <dir>/<design>.stats.toml" in {
      val dir = Files.createTempDirectory("stat-schema")
      val path = StatSchema.emit(dir.toString, "NzeaCore", schema(Seq(derive("ratio", "stat_a / stat_b"))))
      assert(path.endsWith("NzeaCore.stats.toml"), path)
      val text = new String(Files.readAllBytes(java.nio.file.Paths.get(path)))
      assert(text.contains("stat_a") && text.contains("ratio"), text)
    }
  }

}
