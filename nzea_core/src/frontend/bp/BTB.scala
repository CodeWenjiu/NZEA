package nzea_core.frontend.bp

import chisel3._
import chisel3.util.log2Ceil

class BTBEntry(tagBits: Int) extends Bundle {
  val tag    = UInt(tagBits.W)
  val target = UInt(32.W)
}

class BTB(size: Int) extends Module {
  require(size > 0 && (size & (size - 1)) == 0, "BTB size must be power of 2")
  private val indexBits = log2Ceil(size)
  private val tagBits   = 32 - indexBits - 2

  val io = IO(new Bundle {
    val read_addr     = Input(UInt(32.W))
    val pc_for_tag    = Input(UInt(32.W))
    val pred_target   = Output(UInt(32.W))
    val pred_hit      = Output(Bool())
    val update        = Input(Bool())
    val update_pc     = Input(UInt(32.W))
    val update_target = Input(UInt(32.W))
  })

  private val index        = io.read_addr(indexBits + 1, 2)
  private val tag          = io.pc_for_tag(31, indexBits + 2)
  private val update_index = io.update_pc(indexBits + 1, 2)
  private val update_tag   = io.update_pc(31, indexBits + 2)

  val tagMem   = SyncReadMem(size, new BTBEntry(tagBits), SyncReadMem.WriteFirst)
  val validBits = RegInit(VecInit(Seq.fill(size)(false.B)))

  val entry   = tagMem.read(index, true.B)       // 1-cycle read latency
  val indexR  = RegNext(index)                   // align valid read with tag read
  io.pred_hit    := validBits(indexR) && entry.tag === tag
  io.pred_target := entry.target

  when(io.update) {
    val wdata = Wire(new BTBEntry(tagBits))
    wdata.tag    := update_tag
    wdata.target := io.update_target
    tagMem.write(update_index, wdata)
    validBits(update_index) := true.B
  }
}
