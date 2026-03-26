package nzea_core.backend.vector

import chisel3._
import chisel3.util.{Mux1H, PopCount, PriorityEncoder}
import nzea_core.{PipeIO, PipelineConnect}
import nzea_core.frontend.PrfReadIO
import nzea_config.NzeaConfig

/** Select stage: pick oldest ready entry, push to per-FU pipeline reg (single VALU port). */
class VectorIssueQueueSelectStage(robIdWidth: Int, pvrAddrWidth: Int, depth: Int)(implicit config: NzeaConfig)
    extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))
    val out = Vec(1, new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))
  })

  private val flush = io.out(0).flush
  val entries       = Reg(Vec(depth, new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))
  val valids        = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val count         = PopCount(valids.asUInt)
  val full          = count >= depth.U
  val firstInvalid  = PriorityEncoder(VecInit((0 until depth).map(i => !valids(i))).asUInt)
  val enqFire       = !flush && io.in.fire

  io.in.ready := !full && !flush

  val fuReady  = io.out(0).ready
  val rs1Ready = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val rs2Ready = RegInit(VecInit(Seq.fill(depth)(false.B)))

  val canIssue = Wire(Vec(depth, Bool()))
  for (i <- 0 until depth) {
    // Scaffold: after enqueue both true; replace with PRF-ready + bypass later.
    canIssue(i) := valids(i) && rs1Ready(i) && rs2Ready(i) && fuReady
  }

  val firstReadyIdx = PriorityEncoder(canIssue.asUInt)
  val anyCanIssue   = canIssue.asUInt.orR
  val selOneHot     = VecInit((0 until depth).map(i => i.U === firstReadyIdx))

  val raw = entries
  val e   = Wire(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth))
  e.rob_id  := Mux1H(selOneHot, raw.map(_.rob_id))
  e.valu_op := Mux1H(selOneHot, raw.map(_.valu_op))
  e.p_vs1   := Mux1H(selOneHot, raw.map(_.p_vs1))
  e.p_vs2   := Mux1H(selOneHot, raw.map(_.p_vs2))
  e.p_vd    := Mux1H(selOneHot, raw.map(_.p_vd))
  e.imm     := Mux1H(selOneHot, raw.map(_.imm))

  io.out(0).valid := anyCanIssue
  io.out(0).bits  := e

  val issueFire = anyCanIssue && io.out(0).ready
  val deqFire   = !flush && issueFire

  when(flush) {
    for (i <- 0 until depth) { valids(i) := false.B }
  }

  when(deqFire) { valids(firstReadyIdx) := false.B }

  when(enqFire) {
    entries(firstInvalid) := io.in.bits
    rs1Ready(firstInvalid) := true.B
    rs2Ready(firstInvalid) := true.B
    valids(firstInvalid)   := true.B
  }
}

/** Read stage: fetch vs1/vs2 from PVR, build [[ValuInput]]. */
class VectorIssueQueueReadStage(robIdWidth: Int, pvrAddrWidth: Int)(implicit config: NzeaConfig) extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Vec(1, new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth))))
    val prf    = Vec(2, new PrfReadIO(pvrAddrWidth))
    val toValu = new PipeIO(new ValuInput(robIdWidth, pvrAddrWidth))
  })

  private val flush = io.toValu.flush
  io.in(0).flush := flush
  io.in(0).ready := io.toValu.ready

  val e = io.in(0).bits
  io.prf(0).addr := e.p_vs1
  io.prf(1).addr := e.p_vs2

  val op = ValuOp.safe(e.valu_op)._1

  io.toValu.valid := io.in(0).valid
  io.toValu.bits.valu_op := op
  io.toValu.bits.vs1     := io.prf(0).data
  io.toValu.bits.vs2     := io.prf(1).data
  io.toValu.bits.imm     := e.imm
  io.toValu.bits.p_vd    := e.p_vd
  io.toValu.bits.rob_id  := e.rob_id
}

/** Vector issue queue: select → pipeline reg → PVR read → [[VALU]]. */
class VectorIssueQueue(robIdWidth: Int)(implicit config: NzeaConfig) extends Module {
  private val pvrAddrWidth = config.pvrAddrWidth
  private val depth        = config.viqDepthActual

  val io = IO(new Bundle {
    val in       = Flipped(new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))
    val prf_read = Vec(2, new PrfReadIO(pvrAddrWidth))
    val toValu   = new PipeIO(new ValuInput(robIdWidth, pvrAddrWidth))
  })

  val s1   = Module(new VectorIssueQueueSelectStage(robIdWidth, pvrAddrWidth, depth))
  val s2   = Module(new VectorIssueQueueReadStage(robIdWidth, pvrAddrWidth))
  val pipe = Wire(new PipeIO(new VectorIssueQueueEntry(robIdWidth, pvrAddrWidth)))

  s1.io.in <> io.in

  s2.io.in(0).valid := pipe.valid
  s2.io.in(0).bits  := pipe.bits
  pipe.ready        := s2.io.in(0).ready
  pipe.flush        := s2.io.in(0).flush
  PipelineConnect(s1.io.out(0), pipe)

  for (j <- 0 until 2) {
    io.prf_read(j) <> s2.io.prf(j)
  }
  io.toValu <> s2.io.toValu
}
