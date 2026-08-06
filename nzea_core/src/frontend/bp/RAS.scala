package nzea_core.frontend.bp

import chisel3._
import chisel3.util.log2Ceil

/** Commit-side RAS update: a ret committed (drives the RAS pop). */
class RasUpdate extends Bundle {
  val is_ret = Bool()
}

/** Return Address Stack: small circular buffer of return addresses.
  *
  * Push is driven from the execution side (a call that reaches the BRU is a
  * real call — early enough for the ret fetch to read it, no speculative
  * pollution); pop is driven from the commit side (a ret commits exactly
  * once, so a mispredicted ret that re-executes cannot double-pop). The
  * fetch side reads `top` combinationally to redirect a ret to the predicted
  * return address.
  */
class RAS(depth: Int) extends Module {
  require(depth > 0 && (depth & (depth - 1)) == 0, "RAS depth must be power of 2")

  val io = IO(new Bundle {
    val push      = Input(Bool())
    val push_data = Input(UInt(32.W))
    val pop       = Input(Bool())
    val top       = Output(UInt(32.W))
    val top_valid = Output(Bool())
  })

  private val ptrWidth = log2Ceil(depth + 1)
  private val stack = Reg(Vec(depth, UInt(32.W)))
  private val ptr   = RegInit(0.U(ptrWidth.W)) // number of valid entries

  when(io.push && !io.pop && ptr < depth.U) {
    stack(ptr(log2Ceil(depth) - 1, 0)) := io.push_data
    ptr := ptr + 1.U
  }.elsewhen(io.pop && !io.push && ptr > 0.U) {
    ptr := ptr - 1.U
  }
  // push and pop in the same cycle: keep the stack unchanged (a call and a
  // return never resolve in the same cycle, but be safe). Popping an empty
  // stack is ignored so ptr can never wrap to a bogus top_valid.

  io.top := stack(ptr(log2Ceil(depth) - 1, 0) - 1.U) // valid only when top_valid
  io.top_valid := ptr =/= 0.U
}
