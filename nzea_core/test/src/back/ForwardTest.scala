package nzea_core.back

import nzea_core.config.BypassLevel
import nzea_core.isa.Isa
import nzea_rtl.TestFlow
import utest.*

/** Structural checks: the forward network's column — the shape it broadcasts, and the level and queue it is cut to.
  *
  * The level is the column's `armed` bit: a writer the level cannot forward is broadcast unarmed, which is what keeps a
  * read that names it waiting. The column's shape and the writers' identity do not move with the level — the same
  * offers carry the same registers and values in every configuration.
  */
object ForwardTest extends TestSuite:

    /** Lowerings are memoized per configuration: a generator object elaborates a given parameter once per process
      * (`elaboratedModules`), so a second lowering of the same parameters writes no module and the link would fail.
      */
    private val lowerings = scala.collection.mutable.Map[String, String]()

    private def forwardSv(level: BypassLevel, depth: Int): String =
        lowerings.getOrElseUpdate(
            s"${level.name}:$depth",
            TestFlow.lower(
                Forward.moduleName(ForwardParameter(Isa("riscv32i"), 0x80000000L, level, depth)),
                cfg =>
                    Forward.main(
                        Array(
                            "config",
                            cfg.toString,
                            "--isa",
                            "riscv32i",
                            "--defaultPc",
                            "2147483648",
                            "--bypass",
                            level.name,
                            "--lsqDepth",
                            depth.toString
                        )
                    ),
                cfg => Forward.main(Array("design", cfg.toString))
            )
        )

    val tests = Tests:
        test("the level is the column's armed bit"):
            val commit = forwardSv(BypassLevel.Commit, 0)
            val executed = forwardSv(BypassLevel.Execute, 0)
            val none = forwardSv(BypassLevel.None, 0)
            // The retiring write's verdict crosses as a bit — the write itself is the middle's register, matched there.
            assert(commit.contains("assign commit_armed = 1'h1"))
            assert(!none.contains("assign commit_armed = 1'h1"))
            // The slot's writer is armed only where the level reaches the completing value — below it, the read waits
            // the instruction's whole residence.
            assert(executed.contains("& exec_valid"))
            assert(!commit.contains("& exec_valid"))

        // The column is the whole wait rule and the queue's unfinished entries are part of it: an entry's armed bit is
        // its own `done`, so the load still on the bus is offered unarmed — which is what keeps a read behind it
        // waiting.
        test("the queue's unfinished entries ride the column"):
            val queued = forwardSv(BypassLevel.Execute, 4)
            assert(queued.contains("& view_entries_0_done"))
            assert(queued.contains("assign offers_4_armed"))
