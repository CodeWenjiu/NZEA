package nzea_core

import chisel3._
import chisel3.util.Decoupled
import nzea_config.NzeaConfig

/** Core module: builds bus from config, passes to IFU; exposes IFU memory bus. */
class Core(implicit config: NzeaConfig) extends Module {
  private val addrWidth = config.width
  private val dataWidth = config.width
  private val ifuBusGen = () => new CoreBusReadOnly(addrWidth, dataWidth)

  val io = IO(new Bundle {
    val bus       = ifuBusGen()
    val idDecoded = Decoupled(new frontend.IDUOut(addrWidth))
  })

  val ifu = Module(new frontend.IFU(ifuBusGen, config.defaultPc))
  val idu = Module(new frontend.IDU(addrWidth))

  val if2id = PipelineReg(ifu.io.out)
  if2id <> idu.io.in

  io.bus <> ifu.io.bus

  idu.io.gpr_wr.valid := false.B
  idu.io.gpr_wr.bits.addr := 0.U
  idu.io.gpr_wr.bits.data := 0.U

  io.idDecoded <> idu.io.out
}
