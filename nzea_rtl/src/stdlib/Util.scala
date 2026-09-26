package nzea_rtl.stdlib

import java.lang.foreign.Arena

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

/** Small helpers the DSL does not ship. Named after the Chisel idioms the core was written with so ports stay
  * recognisable; the ports themselves are not mechanical, so the names follow Scala conventions.
  */

/** `ceil(log2(x))`, `0` for `x <= 1`. */
def log2Ceil(x: Int): Int =
  require(x >= 1, s"log2Ceil expects a positive value, got $x")
  var value = x - 1
  var bits = 0
  while value > 0 do
    value = value >> 1
    bits += 1
  bits

/** Replicate a 1-bit value `n` times (Chisel `Fill`); `n >= 1`. */
def fill(n: Int, bit: Referable[Bool])(using
    Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
): Node[Bits] =
  require(n >= 1, s"fill expects n >= 1, got $n")
  // `##` propagates `Const` statically; pin the accumulator to a plain node so the fold type-checks
  // regardless of whether the caller passed a constant or a dynamic bit.
  val one: Node[Bits] = Node(bit.asBits)
  (1 until n).foldLeft(one)((acc, _) => one ## acc)
