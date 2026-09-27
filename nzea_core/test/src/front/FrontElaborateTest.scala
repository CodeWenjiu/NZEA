package nzea_core.front

import nzea_core.front.ifu.{IbusUser, Ifu}
import nzea_core.isa.{Backend, Gpr}
import nzea_core.back.OpWindow
import nzea_config.core.{BypassConfig, CoreConfig, Isa}
import nzea_rtl.TestFlow
import nzea_rtl.stdlib.*
import utest.*

/** Structural checks: the front's boundary is exactly the fetcher's bus, the redirect and write-back it is steered by,
  * and the decoded stream the middle consumes — with the fetcher and decoder instantiated inside it.
  */
object FrontElaborateTest extends TestSuite:

  private val width = 32
  private val module = Front.moduleName(FrontParameter(Isa("riscv32i"), 0x80000000L, BypassConfig.writeback))

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
          "--bypass",
          "writeback"
        )
      ),
    cfg => Front.main(Array("design", cfg.toString))
  )

  private lazy val ports = TestFlow.ports(sv, module)

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
          "redirect_valid" -> 1,
          "redirect_bits" -> width,
          "arf_write_valid" -> 1,
          "arf_write_bits_rd_index" -> Gpr.indexWidth,
          "arf_write_bits_data" -> width,
          "flush" -> 1,
          "out_valid" -> 1,
          "out_ready" -> 1,
          "out_bits_pc" -> width,
          "out_bits_imm" -> width,
          "out_bits_rd_index" -> Gpr.indexWidth,
          "out_bits_src_a" -> width,
          "out_bits_src_b" -> width,
          "out_bits_src_c" -> width,
          "out_bits_backend" -> Backend.width,
          "out_bits_op" -> OpWindow.width(CoreConfig(Isa("riscv32i"), 0x80000000L)),
          "out_bits_csr_addr" -> 12
        )
      )

    test("the front instantiates the fetcher and the decoder"):
      assert(sv.contains("module Ifu"))
      assert(sv.contains("module Idu"))
