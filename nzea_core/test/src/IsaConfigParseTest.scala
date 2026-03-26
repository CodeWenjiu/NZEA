package nzea_core

import nzea_config.IsaConfig
import org.scalatest.funsuite.AnyFunSuite

class IsaConfigParseTest extends AnyFunSuite {
  test("riscv32im_zve32x_zvl128b") {
    val c = IsaConfig.parse("riscv32im_zve32x_zvl128b")
    assert(c.xlen === 32)
    assert(c.hasM)
    assert(c.hasEmbeddedVector)
    assert(c.hasZve32x)
    assert(c.zvlBits.contains(128))
  }

  test("riscv32i base only") {
    val c = IsaConfig.parse("riscv32i")
    assert(c.extensions === Set('i'))
    assert(c.namedExtensions.isEmpty)
    assert(c.zvlBits.isEmpty)
  }
}
