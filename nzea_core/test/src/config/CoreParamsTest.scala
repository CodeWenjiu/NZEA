package nzea_core.config

import nzea_core.isa.Isa

import utest.*

/** The resolver is a pure function of the configuration, so both the happy path and the failures are unit tests — no
  * elaboration, no FFM/MLIR, no testbench.
  */
object CoreParamsTest extends TestSuite:

    private val config = CoreConfig(Isa("riscv32i"), 0x80000000L)
    private val declared = Seq(OpProducer("int", 0, 17), OpProducer("mem", 1, 9))

    val tests = Tests:
        test("the longest window sizes the shared field"):
            val params = CoreParams(config, dispatchWidth = 1, declared)
            println(params.report)
            assert(params.opWidth == 17)
            assert(params.widestOp == "int")
            assert(params.dispatchWidth == 1)
            assert(params.dataWidth == 32)
            assert(params.pcWidth == 32)

        test("adding a wider producer moves the field for everyone"):
            val params = CoreParams(config, dispatchWidth = 2, declared :+ OpProducer("fp", 2, 25))
            println(params.report)
            assert(params.opWidth == 25)
            assert(params.widestOp == "fp")

        test("a declaration that breaks the contract fails before any hardware exists"):
            val outOfField =
                intercept[IllegalArgumentException](
                    CoreParams(config, dispatchWidth = 1, declared :+ OpProducer("fp", 2, 8))
                )
            println(outOfField.getMessage)
            assert(outOfField.getMessage.contains("dispatch id does not fit the 1-bit field: fp=2"))

            val duplicate =
                intercept[IllegalArgumentException](
                    CoreParams(config, dispatchWidth = 2, declared :+ OpProducer("int", 2, 8))
                )
            assert(duplicate.getMessage.contains("duplicate producer names: int, mem, int"))

            val empty = intercept[IllegalArgumentException](CoreParams(config, dispatchWidth = 1, Seq.empty))
            assert(empty.getMessage.contains("no op producer declared"))
