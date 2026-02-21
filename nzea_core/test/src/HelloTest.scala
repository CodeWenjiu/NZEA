package nzea_core
import org.scalatest.funsuite.AnyFunSuite

class NzeaCoreTest extends AnyFunSuite {
  test("HelloWorld Test") {
    Elaborate.main(Array.empty)
    assert(true)
  }
}
