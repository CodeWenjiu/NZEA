package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, Valid}
import nzea_config.{FuConfig, CoreConfig}

/** PRF write port: addr, data. Shared by all FU completions. */
class PrfWriteBundle(prfAddrWidth: Int) extends Bundle {
  val addr = UInt(prfAddrWidth.W)
  val data = UInt(32.W)
}

/** Commit / IQ drive addr; data returned from PRF or bypass merge in Core. */
class PrfReadIO(prfAddrWidth: Int) extends Bundle {
  val addr = Output(UInt(prfAddrWidth.W))
  val data = Input(UInt(32.W))
}

/** Raw PRF read result (before bypass), for IQ enqueue operand readiness. */
class PrfRawRead(prfAddrWidth: Int) extends Bundle {
  val data  = UInt(32.W)
  val ready = Bool()
}

/** One combinational read port: addr in, data + architectural ready out. */
class PrfReadPort(prfAddrWidth: Int) extends Bundle {
  val addr  = Input(UInt(prfAddrWidth.W))
  val data  = Output(UInt(32.W))
  val ready = Output(Bool())
}

/** Physical register file only: banked regs + ready bits, multi-port read, write from WBU, clear on rename alloc. */
class Prf(numWritePorts: Int, numReadPorts: Int)(implicit config: CoreConfig) extends Module {
  val prfAddrWidth = config.prfAddrWidth
  val prfDepth     = config.prfDepth

  val io = IO(new Bundle {
    val write      = Input(Vec(numWritePorts, Valid(new PrfWriteBundle(prfAddrWidth))))
    /** New mapping consumes p_rd: clear ready until FU writes. */
    val allocClear = Input(Valid(UInt(prfAddrWidth.W)))
    val read       = Vec(numReadPorts, new PrfReadPort(prfAddrWidth))
  })

  private val numBanks  = 4
  private val bankDepth = 16
  require(prfDepth == numBanks * bankDepth, s"prfDepth=$prfDepth must equal numBanks*bankDepth")

  val bank_regs  = RegInit(VecInit(Seq.tabulate(numBanks)(_ => VecInit(Seq.fill(bankDepth)(0.U(32.W))))))
  val bank_ready = RegInit(VecInit(Seq.tabulate(numBanks)(b =>
    VecInit(Seq.tabulate(bankDepth)(i => (b * bankDepth + i) < 32).map(_.B))
  )))

  for (bank <- 0 until numBanks) {
    for (idx <- 0 until bankDepth) {
      val writeSel = (0 until numWritePorts).map { i =>
        io.write(i).valid &&
        io.write(i).bits.addr(prfAddrWidth - 1, prfAddrWidth - 2) === bank.U &&
        io.write(i).bits.addr(prfAddrWidth - 3, 0) === idx.U
      }
      val anyWrite = writeSel.reduce((a, b) => a || b)
      val writeData = Mux1H(writeSel, (0 until numWritePorts).map(i => io.write(i).bits.data))
      when(anyWrite) {
        bank_regs(bank)(idx) := writeData
        bank_ready(bank)(idx) := true.B
      }
    }
  }

  when(io.allocClear.valid && io.allocClear.bits =/= 0.U) {
    val p_rd = io.allocClear.bits
    val bank = p_rd(prfAddrWidth - 1, prfAddrWidth - 2)
    val idx  = p_rd(prfAddrWidth - 3, 0)
    bank_ready(bank)(idx) := false.B
  }

  private def readPrf(addr: UInt): (UInt, Bool) = {
    val bankSel = (0 until numBanks).map(b => addr(prfAddrWidth - 1, prfAddrWidth - 2) === b.U)
    val idx     = addr(prfAddrWidth - 3, 0)
    val data    = Mux(addr === 0.U, 0.U(32.W), Mux1H(bankSel, (0 until numBanks).map(b => bank_regs(b)(idx))))
    val ready   = Mux(addr === 0.U, true.B, Mux1H(bankSel, (0 until numBanks).map(b => bank_ready(b)(idx))))
    (data, ready)
  }

  for (p <- 0 until numReadPorts) {
    val (d, r) = readPrf(io.read(p).addr)
    io.read(p).data  := d
    io.read(p).ready := r
  }
}

object Prf {
  def numReadPorts(implicit config: CoreConfig): Int =
    2 + 1 + FuConfig.numIssuePorts * 2

  def apply(implicit config: CoreConfig): Prf =
    Module(new Prf(FuConfig.numPrfWritePorts, numReadPorts))
}

/** Operand / commit read merge with bypass (not part of Prf storage). */
object PrfBypass {
  def mergeOperand(
    addr: UInt,
    prfData: UInt,
    prfReady: Bool,
    bypassL1: Vec[Valid[PrfWriteBundle]],
    prfWrite: Vec[Valid[PrfWriteBundle]]
  )(implicit config: CoreConfig): (UInt, Bool) = {
    val prfAddrWidth = addr.getWidth
    val bypassPorts  = FuConfig.prfWritePorts(config).zipWithIndex
    val level1Sel    = bypassPorts.map { case (_, i) => bypassL1(i).valid && bypassL1(i).bits.addr === addr }
    val level2Sel    = bypassPorts.map { case (_, i) => prfWrite(i).valid && prfWrite(i).bits.addr === addr }
    val bypassSel    = level1Sel ++ level2Sel
    val bypassHit =
      if (bypassPorts.isEmpty) false.B
      else bypassSel.reduce((a, b) => a || b)
    val bypassData =
      if (bypassPorts.isEmpty) 0.U(32.W)
      else
        Mux1H(
          bypassSel,
          bypassPorts.map { case (_, i) => bypassL1(i).bits.data } ++ bypassPorts.map { case (_, i) => prfWrite(i).bits.data }
        )
    val data  = Mux(addr === 0.U, 0.U(32.W), Mux(bypassHit, bypassData, prfData))
    val ready = (addr === 0.U) || bypassHit || prfReady
    (data, ready)
  }

  /** Commit rd_value: same as old ISU path (writeback bypass only, no level1). */
  def mergeCommitData(
    addr: UInt,
    prfData: UInt,
    prfWrite: Vec[Valid[PrfWriteBundle]]
  )(implicit config: CoreConfig): UInt = {
    val bypassPorts = FuConfig.prfWritePorts(config).zipWithIndex
    val level2Sel   = bypassPorts.map { case (_, i) => prfWrite(i).valid && prfWrite(i).bits.addr === addr }
    val commitWbBypassHit =
      if (bypassPorts.isEmpty) false.B
      else level2Sel.reduce((a, b) => a || b)
    val commitWbBypassData = Mux1H(level2Sel, bypassPorts.map { case (_, i) => prfWrite(i).bits.data })
    Mux(addr === 0.U, 0.U(32.W), Mux(commitWbBypassHit, commitWbBypassData, prfData))
  }
}
