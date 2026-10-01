package nzea_rtl

import utest.*

/** What the flow leaves behind when a run ends.
  *
  * Since a run finds its intermediates by diffing the working directory, one left behind by a *failed* elaboration is
  * not inert: the next run counts it as pre-existing and drops its module from the linked design, which is how a dump
  * ends up instantiating a module it never defines.
  */
object ElaborateTest extends TestSuite:

    val tests = Tests:
        test("an elaboration that fails partway still leaves no .mlirbc behind"):
            val module = "ElaborateTestModule"
            val intermediate = os.pwd / s"$module.mlirbc"
            val scratch = os.temp.dir(prefix = "nzea-elab-test")
            try
                val thrown = intercept[RuntimeException]:
                    Elaborate.withLinked(
                        module,
                        scratch,
                        _ => (),
                        // What a generator does before it gives up: write its module, then throw.
                        _ =>
                            os.write.over(intermediate, "the module a failed elaboration would have lowered")
                            throw new RuntimeException("elaboration failed")
                    )(_ => ())
                assert(thrown.getMessage == "elaboration failed")
                assert(!os.exists(intermediate))
            finally
                os.remove.all(scratch)
                os.remove.all(intermediate)
