package nzea_core.back

import nzea_core.{BpuLevel, BypassLevel, CoreConfig}
import nzea_core.back.mem.{DbusUser, MemPath, MmioWindow}
import nzea_core.isa.{Backend, CsrType, Gpr, Isa}
import nzea_rtl.{RtlFlow, StatScope, TestFlow}
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the backend's boundary (the issued uop in, the completion and the data bus out) and the paths it
  * instantiates — including that the M units only exist for an ISA that has M.
  */
object BackPathTest extends TestSuite:

  private val width = 32
  private val window = "0x10000000:0x1000"

  private val module =
    Back.moduleName(
      BackParameter(
        Isa("riscv32i"),
        0x80000000L,
        RtlFlow.Yosys,
        Seq(window),
        BypassLevel.Commit,
        BpuLevel.Bimodal(128, 8),
        StatScope.root
      )
    )

  private def elaborate(isa: String, level: BypassLevel = BypassLevel.Commit): String =
    TestFlow.lower(
      module,
      cfg =>
        Back.main(
          Array(
            "config",
            cfg.toString,
            "--isa",
            isa,
            "--defaultPc",
            "2147483648",
            "--flow",
            "yosys",
            "--mmioRanges",
            window,
            "--bypass",
            level.name,
            "--bpu",
            "bimodal:128:8",
            "--stats",
            "root"
          )
        ),
      cfg => Back.main(Array("design", cfg.toString))
    )

  private lazy val rv32i = elaborate("riscv32i")
  private lazy val rv32im = elaborate("riscv32im")

  val tests = Tests:
    test("the backend's boundary is the issued uop, the completion report and the data bus"):
      assert(
        TestFlow.ports(rv32i, module) == Map(
          "clock" -> 1,
          "resetN" -> 1,
          "issue_valid" -> 1,
          "issue_first" -> 1,
          "issue_bits_pc" -> width,
          "issue_bits_next_pc" -> width,
          "issue_bits_fall_through" -> width,
          "issue_bits_imm" -> width,
          "issue_bits_src_a" -> width,
          "issue_bits_src_b" -> width,
          "issue_bits_src_c" -> width,
          "issue_bits_backend" -> Backend.width,
          "issue_bits_op" -> OpWindow.width(CoreConfig(Isa("riscv32i"), 0x80000000L)),
          "issue_bits_csr_type" -> CsrType.width,
          "issue_bits_csr_rdata" -> width,
          "issue_bits_rd_index" -> Gpr.indexWidth,
          "bp_update_valid" -> 1,
          "bp_update_bits_pc" -> width,
          "bp_update_bits_taken" -> 1,
          "bp_update_bits_target" -> width,
          "complete_valid" -> 1,
          "complete_bits_data" -> width,
          "complete_bits_next_pc" -> width,
          "complete_bits_csr_type" -> CsrType.width,
          "complete_bits_csr_data" -> width,
          "complete_bits_is_mmio" -> 1,
          "complete_bits_is_branch" -> 1,
          "complete_bits_pred_next_pc" -> width,
          "dbus_req_valid" -> 1,
          "dbus_req_ready" -> 1,
          "dbus_req_bits_addr" -> width,
          "dbus_req_bits_wdata" -> width,
          "dbus_req_bits_wen" -> 1,
          "dbus_req_bits_wstrb" -> (width / 8),
          "dbus_req_bits_user" -> DbusUser.userWidth,
          "dbus_req_bits_id" -> MemPath.busIdWidth,
          "dbus_resp_valid" -> 1,
          "dbus_resp_ready" -> 1,
          "dbus_resp_bits_data" -> width,
          "dbus_resp_bits_user" -> DbusUser.userWidth,
          "dbus_resp_bits_id" -> MemPath.busIdWidth,
          "bypass_rs1_index" -> Gpr.indexWidth,
          "bypass_rs2_index" -> Gpr.indexWidth,
          "bypass_commit_valid" -> 1,
          "bypass_commit_bits_rd_index" -> Gpr.indexWidth,
          "bypass_commit_bits_data" -> width,
          "bypass_rs1_valid" -> 1,
          "bypass_rs1_bits" -> width,
          "bypass_rs2_valid" -> 1,
          "bypass_rs2_bits" -> width
        )
      )

    test("the backend instantiates both execution paths, and the M units only with the M extension"):
      for path <- Seq("IntPath", "MemPath") do assert(rv32i.contains(s"module $path("))
      for unit <- Seq("Mul", "Div") do
        assert(!rv32i.contains(s"module $unit("))
        assert(rv32im.contains(s"module $unit("))

    // The network's sources are the level's prefix of the forwarding ladder: the retiring write answers at commit,
    // the completing result joins it at execute, and a level below both keeps no matching logic at all.
    test("the bypass network's sources are cut to the level"):
      val executed = elaborate("riscv32i", BypassLevel.Execute)
      val none = elaborate("riscv32i", BypassLevel.None)
      assert(rv32i.contains("module Bypass("))
      assert(rv32i.contains("commit_bits_rd_index == rs1_index"))
      assert(!rv32i.contains("exec_bits_rd_index == rs1_index"))
      assert(executed.contains("exec_bits_rd_index == rs1_index"))
      assert(executed.contains("commit_bits_rd_index == rs1_index"))
      assert(!none.contains("commit_bits_rd_index == rs1_index"))
      assert(!none.contains("exec_bits_rd_index == rs1_index"))

    test("mmio windows parse as hex base:size pairs"):
      assert(MmioWindow.parse("0x10000000:0x1000") == (BigInt("10000000", 16), BigInt("1000", 16)))
      assert(MmioWindow.parse("8000_0000:100") == (BigInt("80000000", 16), BigInt("100", 16)))
      intercept[IllegalArgumentException](MmioWindow.parse("0x1000"))
