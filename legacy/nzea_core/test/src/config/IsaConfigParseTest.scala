package nzea_core.config

import nzea_config.core.IsaConfig
import org.scalatest.funsuite.AnyFunSuite

class IsaConfigParseTest extends AnyFunSuite {

  private def ok(isa: String): IsaConfig =
    IsaConfig.parse(isa) match {
      case Right(c)  => c
      case Left(msg) => fail(s"expected valid ISA, got: $msg")
    }

  test("riscv32im") {
    val c = ok("riscv32im")
    assert(c.xlen === 32)
    assert(c.hasM)
  }

  test("riscv32i base only") {
    val c = ok("riscv32i")
    assert(c.extensions === Set('i'))
  }

  test("named extensions are rejected (not supported yet)") {
    assert(IsaConfig.parse("riscv32im_zve32x_zvl128b").isLeft)
    assert(IsaConfig.parse("riscv32im_wjcus0").isLeft)
  }

  test("reject garbage base extension letters") {
    assert(IsaConfig.parse("riscv32sdksa").isLeft)
    assert(IsaConfig.parse("riscv32imk").isLeft)
  }

  test("reject unrecognized base prefix") {
    assert(IsaConfig.parse("garbage").isLeft)
  }

  test("reject base without i or e") {
    assert(IsaConfig.parse("riscv32m").isLeft)
  }

  test("g expands to imafd") {
    val c = ok("riscv32g")
    assert(c.extensions === Set('i', 'm', 'a', 'f', 'd'))
  }

}