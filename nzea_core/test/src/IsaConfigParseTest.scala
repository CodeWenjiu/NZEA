package nzea_core

import nzea_config.IsaConfig
import org.scalatest.funsuite.AnyFunSuite

class IsaConfigParseTest extends AnyFunSuite {
  private def ok(isa: String): IsaConfig =
    IsaConfig.parse(isa) match {
      case Right(c) => c
      case Left(msg) => fail(s"expected valid ISA, got: $msg")
    }

  test("riscv32im_zve32x_zvl128b") {
    val c = ok("riscv32im_zve32x_zvl128b")
    assert(c.xlen === 32)
    assert(c.hasM)
    assert(c.hasEmbeddedVector)
    assert(c.hasZve32x)
    assert(c.zvlBits.contains(128))
  }

  test("riscv32i base only") {
    val c = ok("riscv32i")
    assert(c.extensions === Set('i'))
    assert(c.namedExtensions.isEmpty)
    assert(c.zvlBits.isEmpty)
  }

  test("wjcus0 flag") {
    val c = ok("riscv32im_wjcus0")
    assert(c.hasM)
    assert(c.hasWjcus0)
    assert(!c.hasZve32x)
  }

  test("named extensions after underscore are order-independent") {
    val a = ok("riscv32im_zve32x_wjcus0_zvl128b")
    val b = ok("riscv32im_wjcus0_zvl128b_zve32x")
    assert(a === b)
    assert(a.hasZve32x && a.hasWjcus0 && a.zvlBits.contains(128))
  }

  test("reject garbage base extension letters") {
    assert(IsaConfig.parse("riscv32sdksa").isLeft)
    assert(IsaConfig.parse("riscv32imk").isLeft)
  }

  test("reject unrecognized base prefix") {
    assert(IsaConfig.parse("garbage").isLeft)
  }

  test("reject unknown named extension tokens") {
    assert(IsaConfig.parse("riscv32im_foobar").isLeft)
  }

  test("reject base without i or e") {
    assert(IsaConfig.parse("riscv32m").isLeft)
  }

  test("g expands to imafd") {
    val c = ok("riscv32g")
    assert(c.extensions === Set('i', 'm', 'a', 'f', 'd'))
  }
}
