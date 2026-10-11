package nzea_core.front

import nzea_core.config.{BpuConfig, BpuStrategy, CoreConfig, RasConfig}
import nzea_core.back.{BpUpdate, OpWindow}
import nzea_core.front.ifu.{IbusUser, Ifu}
import nzea_core.isa.{Backend, Gpr, Isa, Srcs}
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

/** The front's contracts: its boundary — exactly the fetcher's bus, the flush event that steers it, and the decoded
  * stream the middle consumes, with the fetcher and decoder instantiated inside it — the user field the fetcher and any
  * ibus peer agree on, and the predictor config's own contract.
  */
object FrontTest extends TestSuite:

    private val width = 32
    private val bpu = Some(BpuConfig(BpuStrategy.Bimodal(64, 4), None))

    private val module = Front.moduleName(FrontParameter(Isa("riscv32i"), 0x80000000L, bpu))

    private lazy val sv = TestFlow.lower(
        module,
        cfg =>
            Front.main(
                Array(
                    "config",
                    cfg.toString,
                    "--isa",
                    "riscv32i",
                    "--defaultPc",
                    "2147483648",
                    "--bpu",
                    "bimodal:64:4"
                )
            ),
        cfg => Front.main(Array("design", cfg.toString))
    )

    private lazy val ports = TestFlow.ports(sv, module)

    private def front(bpu: Option[BpuConfig]): String =
        val module = Front.moduleName(FrontParameter(Isa("riscv32i"), 0x80000000L, bpu))
        TestFlow.lower(
            module,
            cfg =>
                Front.main(
                    Array(
                        "config",
                        cfg.toString,
                        "--isa",
                        "riscv32i",
                        "--defaultPc",
                        "2147483648"
                    ) ++ bpu.toSeq.flatMap(config => Seq("--bpu", config.name))
                ),
            cfg => Front.main(Array("design", cfg.toString))
        )

    private val bimodal = BpuConfig(BpuStrategy.Bimodal(128, 8), None)

    // One elaboration per parameter per JVM (the generator caches what it has already dumped), so the tests share these.
    private lazy val predicted = front(Some(bimodal))
    private lazy val off = front(None)

    val tests = Tests:
        test("the front's boundary is the bus, the steering inputs and the decoded stream"):
            assert(sv.contains(s"module $module("))
            assert(
                ports == Map(
                    "clock" -> 1,
                    "resetN" -> 1,
                    "ibus_req_valid" -> 1,
                    "ibus_req_ready" -> 1,
                    "ibus_req_bits_addr" -> width,
                    "ibus_req_bits_user" -> IbusUser.userWidth(width),
                    "ibus_req_bits_id" -> Ifu.busIdWidth,
                    "ibus_resp_valid" -> 1,
                    "ibus_resp_ready" -> 1,
                    "ibus_resp_bits_data" -> width,
                    "ibus_resp_bits_user" -> IbusUser.userWidth(width),
                    "ibus_resp_bits_id" -> Ifu.busIdWidth,
                    "flush_valid" -> 1,
                    "flush_next_pc" -> width,
                    "bp_update_valid" -> 1,
                    "bp_update_bits_pc" -> width,
                    "bp_update_bits_taken" -> 1,
                    "bp_update_bits_target" -> width,
                    "bp_update_bits_pht_count" -> BpUpdate.phtCountBits,
                    "out_valid" -> 1,
                    "out_ready" -> 1,
                    "out_bits_pc" -> width,
                    "out_bits_next_pc" -> width,
                    "out_bits_fall_through" -> width,
                    "out_bits_pht_count" -> BpUpdate.phtCountBits,
                    "out_bits_imm" -> width,
                    "out_bits_rd_index" -> Gpr.indexWidth,
                    "out_bits_rs1_index" -> Gpr.indexWidth,
                    "out_bits_rs2_index" -> Gpr.indexWidth,
                    "out_bits_srcs" -> Srcs.width,
                    "out_bits_backend" -> Backend.width,
                    "out_bits_op" -> OpWindow.width(CoreConfig(Isa("riscv32i"), 0x80000000L)),
                    "out_bits_csr_addr" -> 12
                )
            )

        test("the front instantiates the fetcher and the decoder"):
            assert(sv.contains("module Ifu"))
            assert(sv.contains("module Idu"))

        test("the config token round-trips, sizes and options and all"):
            val configs = Seq(
                bimodal,
                BpuConfig(BpuStrategy.Bimodal(64, 4), None),
                BpuConfig(BpuStrategy.Bimodal(64, 4), Some(RasConfig(16)))
            )
            for config <- configs do assert(BpuConfig.parse(config.name) == Right(config))

        test("a malformed token is rejected, not defaulted"):
            assert(BpuConfig.parse("bimodal:7:8").isLeft)
            assert(BpuConfig.parse("bimodal:128").isLeft)
            assert(BpuConfig.parse("tage").isLeft)
            assert(BpuConfig.parse("").isLeft)
            assert(BpuConfig.parse("bimodal:64:4+ras:0").isLeft)
            assert(BpuConfig.parse("bimodal:64:4+foo").isLeft)

        test("a configured predictor builds its tables and none builds none"):
            assert(predicted.contains("module Bpu("))
            assert(predicted.contains("module Pht("))
            assert(predicted.contains("module Btb("))
            // The unit is the one place the prediction is made at every configuration; `None` is the same structure
            // without tables.
            assert(off.contains("module Bpu("))
            assert(!off.contains("module Pht("))
            assert(!off.contains("module Btb("))

        test(
            "the tables live in sync rams — the counters 2 bits, the buffer tag beside its target — and none builds none"
        ):
            assert(predicted.contains("SyncRam_dataWidth2_depth128_ports1r1w"))
            // {tag (32 - 3 index - 2) + target (32 - 2)} = 57 bits per entry, 8 entries.
            assert(predicted.contains("SyncRam_dataWidth57_depth8_ports1r1w"))
            assert(!off.contains("SyncRam_"))

        test("the user field is the epoch column plus the fall-through, next-pc, pc and counter columns"):
            for addrWidth <- Seq(32, 64) do
                val user = IbusUser.layout(addrWidth)
                assert(user.offset("epoch") == 0)
                assert(user.fieldWidth("epoch") == IbusUser.epochBits)
                assert(user.offset("next_pc") == IbusUser.epochBits)
                assert(user.fieldWidth("next_pc") == addrWidth)
                assert(user.offset("fall_through") == IbusUser.epochBits + addrWidth)
                assert(user.fieldWidth("fall_through") == addrWidth)
                assert(user.offset("pc") == IbusUser.epochBits + 2 * addrWidth)
                assert(user.fieldWidth("pc") == addrWidth)
                assert(user.offset("pht_count") == IbusUser.epochBits + 3 * addrWidth)
                assert(user.fieldWidth("pht_count") == BpUpdate.phtCountBits)
                assert(user.width == 3 * addrWidth + IbusUser.epochBits + BpUpdate.phtCountBits)
                assert(IbusUser.userWidth(addrWidth) == user.width)

        test("the epoch is a generation counter, not a single bit"):
            // One bit would alias a stream switch that lands an even number of redirects after the fetch was issued.
            assert(IbusUser.epochBits > 1)
