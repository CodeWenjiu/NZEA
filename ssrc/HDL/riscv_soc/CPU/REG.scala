package riscv_soc

import chisel3._
import chisel3.util._

import signal_value._
import config._

// riscv cpu register file

class REG extends Module {
  val io = IO(new Bundle {
    val REG_2_IDU = Output(new BUS_REG_2_IDU)
    val IDU_2_REG = Input(new BUS_IDU_2_REG)
    val REG_2_EXU = Output(new BUS_REG_2_EXU)
    val WBU_2_REG = Input(new BUS_WBU_2_REG)
  })

  val csra_wen = (io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W1 || io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W2) && io.WBU_2_REG.inst_valid === true.B
  val csrb_wen = io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W2 && io.WBU_2_REG.inst_valid === true.B
  val gpr_wen = io.WBU_2_REG.GPR_waddr =/= 0.U && io.WBU_2_REG.inst_valid === true.B

  val gpr = RegInit(VecInit(Seq.fill(15)(0.U(32.W))))

  when(gpr_wen) {
    gpr((io.WBU_2_REG.GPR_waddr - 1.U)(3, 0)) := io.WBU_2_REG.GPR_wdata
  }

  when(io.IDU_2_REG.GPR_Aaddr =/= 0.U){
    io.REG_2_IDU.GPR_Adata := gpr((io.IDU_2_REG.GPR_Aaddr - 1.U)(3, 0))
  }.otherwise{
    io.REG_2_IDU.GPR_Adata := 0.U
  }

  when(io.IDU_2_REG.GPR_Baddr =/= 0.U){
    io.REG_2_IDU.GPR_Bdata := gpr((io.IDU_2_REG.GPR_Baddr - 1.U)(3, 0))
  }.otherwise{
    io.REG_2_IDU.GPR_Bdata := 0.U
  }

  // CSR
  def ADDR_MSTATUS = "h300".U
  def ADDR_MTEVC   = "h305".U
  def ADDR_MSCRATCH= "h340".U
  def ADDR_MEPC    = "h341".U
  def ADDR_MCAUSE  = "h342".U

  def ADDR_MVENDORID = "hF11".U
  def ADDR_MARCHID   = "hF12".U

  val mstatus, mtevc, mepc, mcause, mscratch = RegInit(0.U(32.W))

  io.REG_2_IDU.CSR_rdata := MuxLookup(io.IDU_2_REG.CSR_raddr, 0.U(32.W))(Seq(
    ADDR_MSTATUS   -> mstatus,
    ADDR_MTEVC     -> mtevc,
    ADDR_MSCRATCH  -> mscratch,
    ADDR_MEPC      -> mepc,
    ADDR_MCAUSE    -> mcause,
    ADDR_MVENDORID -> "h79737978".U(32.W), // ysyx
    ADDR_MARCHID   -> "d23060198".U(32.W)  // my id 
  ))

  when(csra_wen) {
    when(io.WBU_2_REG.CSR_waddra === ADDR_MSTATUS){
      mstatus := io.WBU_2_REG.CSR_wdataa
    }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MTEVC){
      mtevc := io.WBU_2_REG.CSR_wdataa
    }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MSCRATCH){
      mscratch := io.WBU_2_REG.CSR_wdataa
    }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MEPC){
      mepc := io.WBU_2_REG.CSR_wdataa
    }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MCAUSE){
      mcause := io.WBU_2_REG.CSR_wdataa
    }
    // csr((io.WBU_2_REG.CSR_waddra - "h300".U)(6, 0)) := io.WBU_2_REG.CSR_wdataa
  }

  when(csrb_wen) {
    when(io.WBU_2_REG.CSR_waddrb === ADDR_MSTATUS){
      mstatus := io.WBU_2_REG.CSR_wdatab
    }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MTEVC){
      mtevc := io.WBU_2_REG.CSR_wdatab
    }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MSCRATCH){
      mscratch := io.WBU_2_REG.CSR_wdatab
    }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MEPC){
      mepc := io.WBU_2_REG.CSR_wdatab
    }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MCAUSE){
      mcause := io.WBU_2_REG.CSR_wdatab
    }
    // csr((io.WBU_2_REG.CSR_waddrb - "h300".U)(6, 0)) := io.WBU_2_REG.CSR_wdatab
  }
}
