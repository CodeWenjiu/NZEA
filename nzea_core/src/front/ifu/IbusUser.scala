package nzea_core.front.ifu

import chisel3._
import chisel3.util.Cat

/** Ibus user field layout: {pred_next_pc, pc, epoch}. epoch tags every request; on redirect it increments. Responses
  * with a stale epoch are digested — the fetch side never needs device-side cancellation.
  */
object IbusUser {
  val epochBits = 4

  def userWidth(addrWidth: Int): Int = addrWidth * 2 + epochBits

  def pack(addrWidth: Int, pred_next_pc: UInt, pc: UInt, epoch: UInt): UInt =
    Cat(pred_next_pc, pc, epoch)

  def epoch(addrWidth: Int, user: UInt): UInt = user(epochBits - 1, 0)

  def pc(addrWidth: Int, user: UInt): UInt =
    user(addrWidth + epochBits - 1, epochBits)

  def predNextPc(addrWidth: Int, user: UInt): UInt =
    user(addrWidth * 2 + epochBits - 1, addrWidth + epochBits)

}

/** Fetch-stage output: pc, inst, and the next-pc prediction carried with it. */
class IFUOut(width: Int) extends Bundle {
  val pc = UInt(width.W)
  val inst = UInt(32.W)
  val pred_next_pc = UInt(width.W)
}