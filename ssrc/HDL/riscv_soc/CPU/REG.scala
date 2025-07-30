package riscv_soc.cpu

import chisel3._
import chisel3.util._

import riscv_soc.bus._
import signal_value._
import riscv_soc.bus._
import config._

// riscv cpu register file

// class REG extends Module {
//   val io = IO(new Bundle {
//     val REG_2_IDU = Output(new BUS_REG_2_IDU)
//     val IDU_2_REG = Input(new BUS_IDU_2_REG)
//     val REG_2_EXU = Output(new BUS_REG_2_EXU)
//     val WBU_2_REG = Input(new BUS_WBU_2_REG)
//   })

//   val csra_wen = (io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W1 || io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W2) && io.WBU_2_REG.inst_valid === true.B
//   val csrb_wen = io.WBU_2_REG.CSR_ctr === CSR_TypeEnum.CSR_R1W2 && io.WBU_2_REG.inst_valid === true.B
//   val gpr_wen = io.WBU_2_REG.GPR_waddr =/= 0.U && io.WBU_2_REG.inst_valid === true.B

//   val gpr = RegInit(VecInit(Seq.fill(31)(0.U(32.W))))

//   when(gpr_wen) {
//     gpr((io.WBU_2_REG.GPR_waddr - 1.U)(4, 0)) := io.WBU_2_REG.GPR_wdata
//   }

//   when(io.IDU_2_REG.GPR_Aaddr =/= 0.U){
//     io.REG_2_IDU.GPR_Adata := gpr((io.IDU_2_REG.GPR_Aaddr - 1.U)(4, 0))
//   }.otherwise{
//     io.REG_2_IDU.GPR_Adata := 0.U
//   }

//   when(io.IDU_2_REG.GPR_Baddr =/= 0.U){
//     io.REG_2_IDU.GPR_Bdata := gpr((io.IDU_2_REG.GPR_Baddr - 1.U)(4, 0))
//   }.otherwise{
//     io.REG_2_IDU.GPR_Bdata := 0.U
//   }

//   // CSR
//   def ADDR_MSTATUS = "h300".U
//   def ADDR_MTEVC   = "h305".U
//   def ADDR_MSCRATCH= "h340".U
//   def ADDR_MEPC    = "h341".U
//   def ADDR_MCAUSE  = "h342".U

//   def ADDR_MVENDORID = "hF11".U
//   def ADDR_MARCHID   = "hF12".U

//   val mstatus, mtevc, mepc, mcause, mscratch = RegInit(0.U(32.W))

//   io.REG_2_IDU.CSR_rdata := MuxLookup(io.IDU_2_REG.CSR_raddr, 0.U(32.W))(Seq(
//     ADDR_MSTATUS   -> mstatus,
//     ADDR_MTEVC     -> mtevc,
//     ADDR_MSCRATCH  -> mscratch,
//     ADDR_MEPC      -> mepc,
//     ADDR_MCAUSE    -> mcause,
//     ADDR_MVENDORID -> "h79737978".U(32.W), // ysyx
//     ADDR_MARCHID   -> "d23060198".U(32.W)  // my id 
//   ))

//   when(csra_wen) {
//     when(io.WBU_2_REG.CSR_waddra === ADDR_MSTATUS){
//       mstatus := io.WBU_2_REG.CSR_wdataa
//     }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MTEVC){
//       mtevc := io.WBU_2_REG.CSR_wdataa
//     }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MSCRATCH){
//       mscratch := io.WBU_2_REG.CSR_wdataa
//     }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MEPC){
//       mepc := io.WBU_2_REG.CSR_wdataa
//     }.elsewhen(io.WBU_2_REG.CSR_waddra === ADDR_MCAUSE){
//       mcause := io.WBU_2_REG.CSR_wdataa
//     }
//     // csr((io.WBU_2_REG.CSR_waddra - "h300".U)(6, 0)) := io.WBU_2_REG.CSR_wdataa
//   }

//   when(csrb_wen) {
//     when(io.WBU_2_REG.CSR_waddrb === ADDR_MSTATUS){
//       mstatus := io.WBU_2_REG.CSR_wdatab
//     }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MTEVC){
//       mtevc := io.WBU_2_REG.CSR_wdatab
//     }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MSCRATCH){
//       mscratch := io.WBU_2_REG.CSR_wdatab
//     }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MEPC){
//       mepc := io.WBU_2_REG.CSR_wdatab
//     }.elsewhen(io.WBU_2_REG.CSR_waddrb === ADDR_MCAUSE){
//       mcause := io.WBU_2_REG.CSR_wdatab
//     }
//     // csr((io.WBU_2_REG.CSR_waddrb - "h300".U)(6, 0)) := io.WBU_2_REG.CSR_wdatab
//   }
// }

object csr_enum {
  def MSTATUS   = "h300".U
  def MTEVC     = "h305".U
  def MSCRATCH  = "h340".U
  def MEPC      = "h341".U
  def MCAUSE    = "h342".U
  def MVENDORID = "hF11".U 
  def MARCHID   = "hF12".U 
}

class REG extends Module {
  val io = IO(new Bundle {
    val IDU_2_REG = Input(new IDU_2_REG)
    val REG_2_IDU = Output(new REG_2_IDU)
    
    val ISU_2_REG = Input(new ISU_2_REG)
    val REG_2_ISU = Output(new REG_2_ISU)

    val WBU_2_REG = Flipped(ValidIO(Input(new WBU_2_REG)))
    val REG_2_WBU = Output(new REG_2_WBU)
  })

  val gpr = RegInit(VecInit(Seq.fill(31)(0.U(32.W))))
  val gpr_waddr = io.WBU_2_REG.bits.gpr_waddr
  val gpr_wdata = io.WBU_2_REG.bits.gpr_wdata
  val gpr_wen = gpr_waddr =/= 0.U

  val gpr_rs1addr = io.IDU_2_REG.rs1_addr
  val gpr_rs2addr = io.IDU_2_REG.rs2_addr

  io.REG_2_IDU.rs1_val := Mux(gpr_rs1addr === 0.U, 0.U, gpr((gpr_rs1addr - 1.U)(4, 0)))
  io.REG_2_IDU.rs2_val := Mux(gpr_rs2addr === 0.U, 0.U, gpr((gpr_rs2addr - 1.U)(4, 0)))

  val mtevc, mepc, mcause, mscratch = RegInit(0.U(32.W))
  val mstatus = RegInit("h00001800".U(32.W)) // MSTATUS default value

  io.REG_2_ISU.csr_rdata := MuxLookup(io.ISU_2_REG.csr_raddr, 0.U(32.W))(Seq(
    csr_enum.MSTATUS.asUInt   -> mstatus,
    csr_enum.MTEVC.asUInt     -> mtevc,
    csr_enum.MSCRATCH.asUInt  -> mscratch,
    csr_enum.MEPC.asUInt      -> mepc,
    csr_enum.MCAUSE.asUInt    -> mcause,
    csr_enum.MVENDORID.asUInt -> "h79737978".U(32.W), // ysyx
    csr_enum.MARCHID.asUInt   -> "d23060198".U(32.W), // my id 
  ))

  io.REG_2_WBU.MTVEC := mtevc
  io.REG_2_WBU.MEPC := mepc

  val wb_basic = io.WBU_2_REG.bits.basic

  when(io.WBU_2_REG.valid) {
    when(wb_basic.trap === Trap_type.None) {
      when(gpr_wen) {
        gpr((gpr_waddr - 1.U)(4, 0)) := gpr_wdata
      }

      when(io.WBU_2_REG.bits.CSR_wen) {
        switch(io.WBU_2_REG.bits.CSR_waddr) {
          is(csr_enum.MSTATUS.asUInt) { mstatus := io.WBU_2_REG.bits.CSR_wdata }
          is(csr_enum.MTEVC.asUInt)   { mtevc := io.WBU_2_REG.bits.CSR_wdata }
          is(csr_enum.MSCRATCH.asUInt){ mscratch := io.WBU_2_REG.bits.CSR_wdata }
          is(csr_enum.MEPC.asUInt)    { mepc := io.WBU_2_REG.bits.CSR_wdata }
          is(csr_enum.MCAUSE.asUInt)  { mcause := io.WBU_2_REG.bits.CSR_wdata }
        }
      }
    }.elsewhen(wb_basic.trap =/= Trap_type.Mret) {
      mepc := wb_basic.pc
      mcause := wb_basic.trap.asUInt
    }
  }
}
