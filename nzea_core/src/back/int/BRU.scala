package nzea_core.back.int

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_rtl.{PipeIO, PipelineConnect, PrefixAdder, StatsRegs}
import nzea_config.core.CoreConfig
import nzea_core.back.FuCompletion
import nzea_core.back.PrfWriteBundle
import nzea_core.isa.BruOp

/** BRU input: pc, pred_next_pc, offset (imm), rs1/rs2 for branch compare, bruOp; tag, waddr from the issue side. */
class BruInput(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val pc = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val offset = UInt(32.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val bruOp = BruOp()
  val tag = UInt(tagWidth.W)
  val waddr = UInt(waddrWidth.W)
}

/** BRU stage-0 payload: computed targets/conditions feeding the S1 select + mispredict check. */
class BruS0Out(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val waddr = UInt(waddrWidth.W)
  val pc = UInt(32.W)
  val pred_next_pc = UInt(32.W)
  val target = UInt(32.W)
  val pc_plus_4 = UInt(32.W)
  val is_taken = Bool()
}

/** BRU stage-1 payload: resolved next_pc, flush (mispredict), is_taken; pc_plus_4 for JAL/JALR; for ROB and IFU. */
class BruS1Out(tagWidth: Int, waddrWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val waddr = UInt(waddrWidth.W)
  val pc = UInt(32.W)
  val next_pc = UInt(32.W)
  val pc_plus_4 = UInt(32.W)
  val flush = Bool()
  val is_taken = Bool()
}

/** BRU Stage 0: computes targets (PrefixAdder) and the branch outcome is_taken only. The mispredict check lives in
  * Stage 1 so the wide comparators are not serialized behind the adds. Outputs PipeIO(BruS0Out).
  */
class BRUStage0(tagWidth: Int, waddrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruInput(tagWidth, waddrWidth)))
    val out = new PipeIO(new BruS0Out(tagWidth, waddrWidth))
  })

  val b = io.in.bits
  val bruOpU = b.bruOp.asUInt
  val is_jalr = bruOpU(1)
  // Wide adds use the log-depth prefix adder instead of ripple-carry; pc+4 feeds both the not-taken path and the
  // pred_taken check.
  val pc_plus_4 = PrefixAdder(b.pc, 4.U(32.W))
  val target = PrefixAdder(Mux(is_jalr, b.rs1, b.pc), b.offset)
  val is_jmp = bruOpU(0) || bruOpU(1)
  val eq = b.rs1 === b.rs2
  val ne = b.rs1 =/= b.rs2
  val lt = b.rs1.asSInt < b.rs2.asSInt
  val ge = b.rs1.asSInt >= b.rs2.asSInt
  val ltu = b.rs1 < b.rs2
  val geu = b.rs1 >= b.rs2

  val branchTaken = Mux1H(
    bruOpU,
    Seq(
      true.B,
      true.B,
      eq,
      ne,
      lt,
      ge,
      ltu,
      geu
    )
  )

  val is_taken = is_jmp || branchTaken

  io.out.valid := io.in.valid
  io.out.bits.tag := b.tag
  io.out.bits.waddr := b.waddr
  io.out.bits.pc := b.pc
  io.out.bits.pred_next_pc := b.pred_next_pc
  io.out.bits.target := target
  io.out.bits.pc_plus_4 := pc_plus_4
  io.out.bits.is_taken := is_taken
  io.in.ready := io.out.ready
  io.in.flush := io.out.flush
}

/** BRU Stage 1: selects the actual next_pc (off the S0 add chain), resolves the mispredict, and drives the branch
  * statistics. Outputs PipeIO(BruS1Out).
  */
class BRUStage1(tagWidth: Int, waddrWidth: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruS0Out(tagWidth, waddrWidth)))
    val flush = Input(Bool())
    val out = new PipeIO(new BruS1Out(tagWidth, waddrWidth))
  })

  val b = io.in.bits
  // Select the actual next_pc here (S1), off the S0 add chain; compare both
  // candidates in parallel with the mux and merge the single-bit results.
  val next_pc = Mux(b.is_taken, b.target, b.pc_plus_4)
  val mispredict = Mux(b.is_taken, b.pred_next_pc =/= b.target, b.pred_next_pc =/= b.pc_plus_4)

  // Simulation-only branch statistics (baseline: always-not-taken prediction, so a taken branch is a mispredict).
  if (config.sim) {
    val stats = Module(
      new StatsRegs("BpStats", Seq("stat_bp_branch" -> 32, "stat_bp_mispred" -> 32, "stat_bp_actual_taken" -> 32))
    )
    stats.clock := clock
    stats.reset := reset
    stats.ports("stat_bp_branch").en := io.in.valid
    stats.ports("stat_bp_branch").data := stats.ports("stat_bp_branch").value + 1.U
    stats.ports("stat_bp_mispred").en := io.in.valid && mispredict
    stats.ports("stat_bp_mispred").data := stats.ports("stat_bp_mispred").value + 1.U
    stats.ports("stat_bp_actual_taken").en := io.in.valid && b.is_taken
    stats.ports("stat_bp_actual_taken").data := stats.ports("stat_bp_actual_taken").value + 1.U
  }

  io.out.valid := io.in.valid
  io.out.bits.tag := b.tag
  io.out.bits.waddr := b.waddr
  io.out.bits.pc := b.pc
  io.out.bits.next_pc := next_pc
  io.out.bits.pc_plus_4 := b.pc_plus_4
  io.out.bits.flush := mispredict
  io.out.bits.is_taken := b.is_taken
  io.in.ready := io.out.ready
  io.in.flush := io.flush
}

/** BRU Stage 2 (write-back): receives BruS1Out, outputs the completion to ROB and the redirect target to the fetch
  * side.
  */
class BRUStage2(tagWidth: Int, waddrWidth: Int) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruS1Out(tagWidth, waddrWidth)))
    val flush = Input(Bool())
    val completion = Output(Valid(new FuCompletion(tagWidth)))
    val out = new nzea_rtl.PipeIO(new PrfWriteBundle(waddrWidth))
    val flush_target = Output(UInt(32.W))
  })

  val b = io.in.bits

  io.completion <> FuCompletion.report(
    io.in.valid,
    b.tag,
    is_done = true.B,
    flush = b.flush,
    next_pc = b.next_pc
  )(tagWidth)

  io.out.valid := io.in.valid
  io.out.bits.waddr := b.waddr
  io.out.bits.data := b.pc_plus_4
  io.flush_target := b.next_pc

  io.in.ready := io.out.ready
  io.in.flush := io.flush
}

/** BRU: 3-stage pipeline. S0 computes targets; S1 resolves mispredict; S2 writes back. PipelineConnect internally. */
class BRU(tagWidth: Int, waddrWidth: Int)(implicit config: CoreConfig) extends Module {

  val io = IO(new Bundle {
    val in = Flipped(new PipeIO(new BruInput(tagWidth, waddrWidth)))
    val completion = Output(Valid(new FuCompletion(tagWidth)))
    val out = new nzea_rtl.PipeIO(new PrfWriteBundle(waddrWidth))
    val flush_target = Output(UInt(32.W))
  })

  val s0 = Module(new BRUStage0(tagWidth, waddrWidth))
  val s1 = Module(new BRUStage1(tagWidth, waddrWidth))
  val s2 = Module(new BRUStage2(tagWidth, waddrWidth))

  io.in <> s0.io.in
  io.in.flush := io.out.flush
  s1.io.flush := io.out.flush
  s2.io.flush := io.out.flush
  PipelineConnect(s0.io.out, s1.io.in)
  PipelineConnect(s1.io.out, s2.io.in)
  io.completion <> s2.io.completion
  io.out.valid := s2.io.out.valid
  io.out.bits := s2.io.out.bits
  s2.io.out.ready := io.out.ready
  s2.io.out.flush := io.out.flush
  io.flush_target := s2.io.flush_target
}