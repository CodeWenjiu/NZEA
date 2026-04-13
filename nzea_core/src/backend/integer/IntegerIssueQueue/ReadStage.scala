package nzea_core.backend.integer

import chisel3._
import chisel3.util.Valid
import nzea_core.frontend.{FuType, IssuePortsBundle, PrfReadIO}
import nzea_config.{CoreConfig, FuConfig, FuKind}
import nzea_rtl.PipeIO

/** Stage 2: per-port independent read/dispatch.
  * If pipe is valid, read PRF and drive FU adapter without arbitration.
  * Extracts flush from issuePorts (consumer-driven) and propagates to in.flush.
  */
class IntegerIssueQueueReadStage(robIdWidth: Int, prfAddrWidth: Int, lsqIdWidth: Int)(implicit config: CoreConfig) extends Module {
  private val numPorts = FuConfig.numIssuePorts
  private val issuePortConfigs = FuConfig.issuePorts(config)
  private val portIdxByKind = issuePortConfigs.zipWithIndex.map { case (cfg, idx) => cfg.kind -> idx }.toMap
  require(
    portIdxByKind.size == issuePortConfigs.size,
    s"Duplicate FuKind in issue port config: ${issuePortConfigs.map(_.kind)}"
  )

  private def portIdx(kind: FuKind): Int =
    portIdxByKind.getOrElse(kind, throw new IllegalArgumentException(s"Missing issue port for FuKind.$kind"))
  private def portIdxOpt(kind: FuKind): Option[Int] = portIdxByKind.get(kind)

  private val wakeupHintSpecs: Seq[(Int, Int)] = issuePortConfigs.zipWithIndex.flatMap { case (cfg, idx) =>
    cfg.wakeupHintLatency.map(lat => (idx, lat))
  }
  private val numWakeupHints = wakeupHintSpecs.size

  private val aluIdx = portIdx(FuKind.Alu)
  private val bruIdx = portIdx(FuKind.Bru)
  private val aguIdx = portIdx(FuKind.Agu)
  private val mulIdxOpt = portIdxOpt(FuKind.Mul)
  private val divIdxOpt = portIdxOpt(FuKind.Div)
  private val nnuIdxOpt = portIdxOpt(FuKind.Nnu)
  private val sysuIdx = portIdx(FuKind.Sysu)

  val io = IO(new Bundle {
    val in = Flipped(Vec(numPorts, new PipeIO(new IntegerIssueQueueEntry(robIdWidth, prfAddrWidth, lsqIdWidth))))
    val prf_read = Vec(numPorts, Vec(2, new PrfReadIO(prfAddrWidth)))
    /** Combinational CSR read for SYSU port (addr from [[csr_read_addr]]). */
    val csr_rdata = Input(UInt(32.W))
    /** CSR address for [[csr_rdata]] read; 0 when SYSU port not issuing. */
    val csr_read_addr = Output(UInt(12.W))
    val issuePorts = new IssuePortsBundle(robIdWidth, prfAddrWidth, lsqIdWidth)
    /** Early wakeup hints (valid + p_rd) generated from fixed-latency FU issue points. */
    val wakeup_hints = Output(Vec(numWakeupHints, Valid(UInt(prfAddrWidth.W))))
  })

  private val flush = io.issuePorts.orderedPorts(0).flush
  for (i <- 0 until numPorts) {
    io.in(i).flush := flush
    io.in(i).ready := io.issuePorts.orderedPorts(i).ready
  }

  private def entry(i: Int) = io.in(i).bits
  private def rs1(i: Int) = Mux(entry(i).p_rs1 === 0.U, 0.U(32.W), io.prf_read(i)(0).data)
  private def rs2(i: Int) = Mux(entry(i).p_rs2 === 0.U, 0.U(32.W), io.prf_read(i)(1).data)
  for (i <- 0 until numPorts) {
    io.prf_read(i)(0).addr := entry(i).p_rs1
    io.prf_read(i)(1).addr := entry(i).p_rs2
  }

  private def driveWakeupHint(dst: Valid[UInt], portIdx: Int, latency: Int): Unit = {
    require(latency >= 0, s"wakeupHintLatency must be >= 0, got $latency for port $portIdx")
    if (latency == 0) {
      dst.valid := io.in(portIdx).valid
      dst.bits  := entry(portIdx).p_rd
    } else {
      val validPipe = RegInit(VecInit(Seq.fill(latency)(false.B)))
      val pRdPipe   = Reg(Vec(latency, UInt(prfAddrWidth.W)))
      when(flush) {
        for (k <- 0 until latency) {
          validPipe(k) := false.B
        }
      }.otherwise {
        validPipe(0) := io.in(portIdx).valid
        pRdPipe(0)   := entry(portIdx).p_rd
        for (k <- 1 until latency) {
          validPipe(k) := validPipe(k - 1)
          pRdPipe(k)   := pRdPipe(k - 1)
        }
      }
      dst.valid := validPipe(latency - 1)
      dst.bits  := pRdPipe(latency - 1)
    }
  }

  private def dispatchToFuPorts(): Unit = {
    IssueAdapters.Alu.drive(
      valid = io.in(aluIdx).valid,
      entry = entry(aluIdx),
      rs1 = rs1(aluIdx),
      rs2 = rs2(aluIdx),
      out = io.issuePorts.alu
    )
    IssueAdapters.Bru.drive(
      valid = io.in(bruIdx).valid,
      entry = entry(bruIdx),
      rs1 = rs1(bruIdx),
      rs2 = rs2(bruIdx),
      out = io.issuePorts.bru
    )
    IssueAdapters.Agu.drive(
      valid = io.in(aguIdx).valid,
      entry = entry(aguIdx),
      rs1 = rs1(aguIdx),
      rs2 = rs2(aguIdx),
      out = io.issuePorts.agu
    )
    mulIdxOpt.foreach { i =>
      IssueAdapters.Mul.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.mul.get
      )
    }
    divIdxOpt.foreach { i =>
      IssueAdapters.Div.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.div.get
      )
    }
    nnuIdxOpt.foreach { i =>
      IssueAdapters.Nnu.drive(
        valid = io.in(i).valid,
        entry = entry(i),
        rs1 = rs1(i),
        rs2 = rs2(i),
        out = io.issuePorts.nnu.get
      )
    }
    IssueAdapters.Sysu.drive(
      valid = io.in(sysuIdx).valid,
      entry = entry(sysuIdx),
      rs1 = rs1(sysuIdx),
      csrRdata = io.csr_rdata,
      out = io.issuePorts.sysu
    )
  }

  io.csr_read_addr := Mux(
    io.in(sysuIdx).valid && entry(sysuIdx).fu_type === FuType.SYSU,
    entry(sysuIdx).csr_addr,
    0.U(12.W)
  )

  dispatchToFuPorts()
  for (hintIdx <- wakeupHintSpecs.indices) {
    val (portIdx, latency) = wakeupHintSpecs(hintIdx)
    driveWakeupHint(io.wakeup_hints(hintIdx), portIdx, latency)
  }
}
