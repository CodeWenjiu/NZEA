package riscv_soc

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

class Sub extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(4.W))
    val b = Input(UInt(4.W))
    val c = Output(UInt(4.W))
  })
  io.c := io.a + ~io.b + 1.U

  val ref = io.a - io.b
  assert(io.c === ref)
}

class FormalTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  "Test" should "pass" in {
    verify(new Sub, Seq(BoundedCheck(1), BtormcEngineAnnotation))
  }
}
