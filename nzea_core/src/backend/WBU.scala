package nzea_core.backend

import chisel3._
import chisel3.util.{Decoupled, Mux1H, PriorityEncoder, Queue, Valid}
import nzea_core.backend.fu.{AluOut, AguOut, BruOut, SysuOut}
import nzea_core.frontend.FuType
import nzea_core.CoreBusReadWrite

/** One entry in the Rob: fu_type + rd_index (GPR write address). */
class RobEntry extends Bundle {
  val fu_type  = FuType()
  val rd_index = UInt(5.W)
}

/** Commit message for Debugger/Difftest: next_pc (PC after commit) and GPR write. */
class CommitMsg extends Bundle {
  val valid    = Bool()
  val next_pc  = UInt(32.W)
  val gpr_addr = UInt(5.W)
  val gpr_data = UInt(32.W)
}

/** WB bypass payload: rd and data for ISU to bypass when head commits. */
class WbBypass extends Bundle {
  val rd   = UInt(5.W)
  val data = UInt(32.W)
}

/** WBU: four FU inputs; Rob (Queue) inside; head gives fu_type and rd_index for in-order commit.
  * AGU output goes to MemUnit for actual memory access; dbus exposed for Core.
  */
class WBU(dbusGen: () => CoreBusReadWrite) extends Module {
  val io = IO(new Bundle {
    val alu_in   = Flipped(Decoupled(new AluOut))
    val bru_in   = Flipped(Decoupled(new BruOut))
    val agu_in   = Flipped(Decoupled(new AguOut))
    val sysu_in  = Flipped(Decoupled(new SysuOut))
    val rob_enq  = Flipped(Decoupled(new RobEntry))
    val gpr_wr   = Output(new Bundle {
      val addr = UInt(5.W)
      val data = UInt(32.W)
    })
    val commit_msg     = Output(new CommitMsg)
    val rob_pending_rd = Output(Vec(4, Valid(UInt(5.W))))
    val wb_bypass      = Output(Valid(new WbBypass))
    val dbus    = dbusGen()
  })

  val memUnit = Module(new MemUnit(dbusGen))

  val rob   = Queue(io.rob_enq, 4)
  val head  = rob.bits
  val alu_ok  = rob.valid && (head.fu_type === FuType.ALU)
  val bru_ok  = rob.valid && (head.fu_type === FuType.BRU)
  val lsu_ok  = rob.valid && (head.fu_type === FuType.LSU)
  val sysu_ok = rob.valid && (head.fu_type === FuType.SYSU)

  memUnit.io.req.valid := lsu_ok && io.agu_in.valid
  memUnit.io.req.bits  := io.agu_in.bits
  io.agu_in.ready  := !io.agu_in.valid || (lsu_ok && memUnit.io.req.ready)

  io.alu_in.ready  := !io.alu_in.valid  || alu_ok
  io.bru_in.ready  := !io.bru_in.valid  || bru_ok
  io.sysu_in.ready := !io.sysu_in.valid || sysu_ok

  val lsu_done = lsu_ok && memUnit.io.ready
  val rob_commit = (alu_ok  && io.alu_in.valid) || (bru_ok  && io.bru_in.valid) ||
                   lsu_done || (sysu_ok && io.sysu_in.valid)
  rob.ready := rob_commit

  val rob_rd_slots = RegInit(VecInit(Seq.fill(4)(0.U.asTypeOf(Valid(UInt(5.W))))))
  val enqSlot = PriorityEncoder(rob_rd_slots.map(s => !s.valid))
  when(rob_commit && io.rob_enq.fire) {
    for (i <- 0 until 3) { rob_rd_slots(i) := rob_rd_slots(i + 1) }
    rob_rd_slots(3).valid := true.B
    rob_rd_slots(3).bits := io.rob_enq.bits.rd_index
  }.elsewhen(rob_commit) {
    for (i <- 0 until 3) { rob_rd_slots(i) := rob_rd_slots(i + 1) }
    rob_rd_slots(3).valid := false.B
  }.elsewhen(io.rob_enq.fire) {
    rob_rd_slots(enqSlot).valid := true.B
    rob_rd_slots(enqSlot).bits := io.rob_enq.bits.rd_index
  }
  io.rob_pending_rd := rob_rd_slots

  val lsu_next_pc = RegInit(0.U(32.W))
  when(io.agu_in.fire) { lsu_next_pc := io.agu_in.bits.next_pc }

  val sel = Seq(alu_ok && io.alu_in.valid, bru_ok && io.bru_in.valid, lsu_done, sysu_ok && io.sysu_in.valid)
  val rd_data = Mux1H(sel :+ !sel.reduce(_ || _), Seq(io.alu_in.bits.rd_data, io.bru_in.bits.rd_data, memUnit.io.loadData, io.sysu_in.bits.rd_data, 0.U(32.W)))

  io.gpr_wr.addr := Mux(rob_commit, head.rd_index, 0.U)
  io.gpr_wr.data := rd_data

  val next_pc = Mux1H(sel :+ !sel.reduce(_ || _), Seq(io.alu_in.bits.next_pc, io.bru_in.bits.next_pc, lsu_next_pc, io.sysu_in.bits.next_pc, 0.U(32.W)))
  io.commit_msg.valid    := rob_commit
  io.commit_msg.next_pc  := next_pc
  io.commit_msg.gpr_addr := head.rd_index
  io.commit_msg.gpr_data := rd_data

  io.wb_bypass.valid := rob_commit
  io.wb_bypass.bits.rd   := head.rd_index
  io.wb_bypass.bits.data := rd_data

  io.dbus <> memUnit.io.dbus
}
