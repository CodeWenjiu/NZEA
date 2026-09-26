package nzea_cache

import chisel3._
import chisel3.util._

/** One cache row: tag + data stored together in SRAM. */
class CacheRow(tagBits: Int, lineBits: Int) extends Bundle {
  val tag = UInt(tagBits.W)
  val data = UInt(lineBits.W)
}

/** SRAM arrays (tag+data) and valid bits.
  *
  * Read: 1-cycle latency (SyncReadMem). Write: same-cycle write to SRAM + valid bit. Valid: combinational read port for
  * T1 hit check.
  */
class CacheStorage(
    nSets: Int,
    nWays: Int,
    tagBits: Int,
    lineBits: Int
) extends Module {

  private val indexBits = log2Ceil(nSets)
  private val wayBits = log2Ceil(nWays)

  val io = IO(new Bundle {
    // ── Read port (1-cycle latency) ──
    val rdSetIdx = Input(UInt(indexBits.W))
    val rdEnable = Input(Bool())
    val rdRows = Output(Vec(nWays, new CacheRow(tagBits, lineBits)))

    // ── Write port (same-cycle) ──
    val wrValid = Input(Bool())
    val wrSetIdx = Input(UInt(indexBits.W))
    val wrWay = Input(UInt(wayBits.W))
    val wrTag = Input(UInt(tagBits.W))
    val wrData = Input(UInt(lineBits.W))

    // ── Valid bits (combinational read) ──
    val vdSetIdx = Input(UInt(indexBits.W))
    val vdBits = Output(Vec(nWays, Bool()))
  })

  private val rowType = new CacheRow(tagBits, lineBits)
  private val srams = Seq.fill(nWays)(SyncReadMem(nSets, rowType))

  private val validArray = RegInit(
    VecInit(Seq.fill(nSets)(VecInit(Seq.fill(nWays)(false.B))))
  )

  // ── Read ──
  io.rdRows := VecInit(srams.map(_.read(io.rdSetIdx, io.rdEnable)))

  // ── Write ──
  (0 until nWays).foreach { i =>
    when(io.wrValid && io.wrWay === i.U) {
      val wdata = Wire(rowType.cloneType)
      wdata.tag := io.wrTag
      wdata.data := io.wrData
      srams(i).write(io.wrSetIdx, wdata)
    }
  }

  when(io.wrValid) {
    validArray(io.wrSetIdx)(io.wrWay) := true.B
  }

  // ── Valid read (combinational) ──
  io.vdBits := validArray(io.vdSetIdx)
}
