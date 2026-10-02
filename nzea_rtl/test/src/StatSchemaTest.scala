package nzea_rtl

import java.nio.file.{Files, Paths}

import nzea_rtl.stat.*
import utest.*

/** Schema contract tests: expression grammar, in-order rule resolution, TOML emission. */
object StatSchemaTest extends TestSuite:

    private val Region = StatRegion("g", "group")
    private val a = StatCounter("stat_a", 8, "core", Region, "counter a")
    private val b = StatCounter("stat_b", 8, "core", Region, "counter b")

    private def schema(derived: Seq[StatDerived], counters: Seq[StatCounter] = Seq(a, b)) =
        StatSchema(Seq(Region), counters, derived)

    private def derive(name: String, expr: String) = StatDerived(name, expr, "core", Region, "", 4, "")

    private val values = Map("stat_a" -> 5L, "stat_b" -> 2L)

    val tests = Tests:
        test("expression grammar: precedence, unary minus, parentheses and functions"):
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

        test("expression grammar: rejects malformed input"):
            def rejected(expr: String): Boolean =
                try
                    StatExpr.parse(expr)
                    false
                catch case _: IllegalArgumentException => true
            assert(rejected("1 +"))
            assert(rejected("(1 + 2"))
            assert(rejected("1 2"))
            assert(rejected("bogus(1)"))
            assert(rejected("max(1)"))
            assert(rejected(""))

        test("expression grammar: unavailable instead of fabricated numbers"):
            val node = StatExpr.parse("stat_a / stat_b")
            assert(StatExpr.eval(node, Map("stat_a" -> 5.0, "stat_b" -> 2.0).get(_)) == Some(2.5))
            assert(StatExpr.eval(node, Map("stat_a" -> 5.0, "stat_b" -> 0.0).get(_)).isEmpty)
            assert(StatExpr.eval(node, Map("stat_a" -> 5.0).get(_)).isEmpty)

        test("expression grammar: collects references"):
            assert(StatExpr.refs(StatExpr.parse("stat_a + max(stat_b, 2)")) == Set("stat_a", "stat_b"))

        test("schema validation: accepts a well-formed in-order schema"):
            schema(Seq(derive("ratio", "stat_a / stat_b"), derive("inverse", "1 / ratio"))).validate()

        test("schema validation: rejects a reference that is not a counter or an earlier rule"):
            val e = intercept[IllegalArgumentException] {
                schema(Seq(derive("bad", "stat_missing + 1"))).validate()
            }
            assert(e.getMessage.contains("stat_missing"))

        test("schema validation: rejects a forward reference (cycle by construction)"):
            val e = intercept[IllegalArgumentException] {
                schema(Seq(derive("cpi", "1 / ipc"), derive("ipc", "stat_a / stat_b"))).validate()
            }
            assert(e.getMessage.contains("ipc"))

        test("schema validation: rejects duplicates, unknown regions and shadowing"):
            intercept[IllegalArgumentException] { schema(Seq(derive("r", "1")), Seq(a, a)).validate() }
            intercept[IllegalArgumentException] {
                schema(Seq(StatDerived("r", "1", "core", StatRegion("nope", ""), "", 4, ""))).validate()
            }
            intercept[IllegalArgumentException] { schema(Seq(derive("stat_a", "1"))).validate() }
            intercept[IllegalArgumentException] { schema(Seq(derive("r", "stat_a +"), derive("r", "1"))).validate() }
            intercept[IllegalArgumentException] {
                StatSchema(Seq(Region), Seq(StatCounter("cycle", 8, "core", Region, "")), Seq.empty).validate()
            }

        test("schema validation: rejects a source that is not a lowercase block identifier"):
            for bad <- Seq("Core", "1core", "_core", "co-re", "") do
                intercept[IllegalArgumentException] {
                    schema(Seq.empty, Seq(StatCounter("stat_a", 8, bad, Region, ""))).validate()
                }
                intercept[IllegalArgumentException] {
                    schema(Seq(StatDerived("r", "1", bad, Region, "", 4, ""))).validate()
                }
            schema(Seq(StatDerived("r", "1", "core_int", Region, "", 4, ""))).validate()

        test("schema validation: enforces the consumer limits of the schema"):
            val wide = StatCounter("stat_w", StatSchema.MaxCounterWidth, "core", Region, "")
            val tooWide = StatCounter("stat_w", StatSchema.MaxCounterWidth + 1, "core", Region, "")
            schema(Seq.empty, Seq(wide)).validate()
            schema(Seq(StatDerived("r", "1", "core", Region, "", StatSchema.MaxDigits, ""))).validate()
            intercept[IllegalArgumentException] { schema(Seq.empty, Seq(tooWide)).validate() }
            intercept[IllegalArgumentException] {
                schema(Seq(StatDerived("r", "1", "core", Region, "", StatSchema.MaxDigits + 1, ""))).validate()
            }

        test("evaluation: derives in file order and propagates unavailability"):
            val s = schema(Seq(derive("ipc", "stat_a / stat_b"), derive("cpi", "1 / ipc")))
            val out = s.evaluate(values)
            assert(out("ipc") == Some(2.5))
            assert(out("cpi") == Some(0.4))

            val divZero = s.evaluate(Map("stat_a" -> 5L, "stat_b" -> 0L))
            assert(divZero("ipc").isEmpty)
            assert(divZero("cpi").isEmpty)

        test("toml emission: contains every declaration with its region and source"):
            val toml = schema(Seq(derive("ratio", "stat_a / stat_b"))).toToml("NzeaCore")
            assert(toml.startsWith("schema = 2\n"))
            assert(toml.contains("""design = "NzeaCore""""))
            assert(toml.contains("[[region]]\nname = \"g\"\ndoc = \"group\"\n"))
            assert(toml.contains("[[counter]]\nname = \"stat_a\"\nwidth = 8\nsource = \"core\"\nregion = \"g\"\n"))
            assert(
                toml.contains(
                    "[[derived]]\nname = \"ratio\"\nexpr = \"stat_a / stat_b\"\nsource = \"core\"\nregion = \"g\"\n"
                )
            )
            assert(toml.contains("digits = 4\n"))

        test("toml emission: escapes quotes and backslashes"):
            val toml = StatSchema(Seq(StatRegion("g", "a \"quoted\" doc")), Seq.empty, Seq.empty).toToml("NzeaCore")
            assert(toml.contains("""doc = "a \"quoted\" doc""""))

        test("toml emission: emit writes <dir>/<design>.stats.toml"):
            val dir = Files.createTempDirectory("stat-schema")
            val path = StatSchema.emit(dir.toString, "NzeaCore", schema(Seq(derive("ratio", "stat_a / stat_b"))))
            assert(path.endsWith("NzeaCore.stats.toml"))
            val text = new String(Files.readAllBytes(Paths.get(path)))
            assert(text.contains("stat_a") && text.contains("ratio"))
