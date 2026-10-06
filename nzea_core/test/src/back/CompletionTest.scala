package nzea_core.back

import nzea_core.isa.Isa
import nzea_rtl.{RtlFlow, TestFlow}
import nzea_rtl.stat.StatScope
import utest.*

/** Structural checks: the completion path is the in-order queue when the configuration builds one and the pass-through
  * otherwise, and the middle's contract — one retire stream and one slot pace — has the same shape either way.
  */
object CompletionTest extends TestSuite:

    private val module =
        Completion.moduleName(CompletionParameter(Isa("riscv32i"), 0x80000000L, RtlFlow.Yosys, 0, StatScope.root))

    private def elaborate(depth: Int): String =
        TestFlow.lower(
            module,
            cfg =>
                Completion.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648",
                        "--flow",
                        "yosys",
                        "--stats",
                        "root"
                    ) ++ Seq("--lsqDepth", depth.toString)
                ),
            cfg => Completion.main(Array("design", cfg.toString))
        )

    private lazy val queued = elaborate(4)
    private lazy val plain = elaborate(0)

    val tests = Tests:
        test("the completion path is the queue when the configuration builds one"):
            assert(queued.contains("module Lsq("))
            assert(!plain.contains("module Lsq("))

        test("the retire stream and the slot pace have the same shape either way"):
            for sv <- Seq(queued, plain) do
                val ports = TestFlow.ports(sv, module)
                assert(ports("retire_valid") == 1)
                assert(ports("retire_bits_mispredicted") == 1)
                assert(ports("pace") == 1)
                assert(ports("canAccept") == 1)
                assert(ports("flushRisk") == 1)

        test("the entries view is the queue's, or a constant when none is built"):
            assert(queued.contains("entries_3_valid"))
            assert(!plain.contains("entries_1_valid"))
