package riscv_soc

import chisel3._
import chisel3.util._
import chisel3.util.MuxLookup

import signal_value._
import bus_state._
import config._

// riscv cpu analogic and logical unit

class ALU_catch extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle{
    val clock = Input(Clock())
    val valid = Input(Bool())
    val pc    = Input(UInt(32.W))
  })
  val code = 
  s"""module ALU_catch(
  |    input clock,
  |    input valid,
  |    input [31:0] pc
  |);
  |  import "DPI-C" function void ALU_catch(input bit [31:0] pc);
  |  always @(posedge clock) begin
  |     if(valid) begin
  |       ALU_catch(pc);
  |     end
  |  end
  |endmodule
  """

  setInline("ALU_catch.v", code.stripMargin)
}

class ALU extends Module {
  val io = IO(new Bundle {
    val IDU_2_EXU = Flipped(Decoupled(Input(new BUS_IDU_2_EXU)))

    val EXU_2_WBU = Decoupled(Output(new BUS_EXU_2_WBU))
  })

  io.EXU_2_WBU.valid := io.IDU_2_EXU.valid
  io.IDU_2_EXU.ready  := io.EXU_2_WBU.ready

  // ALU operation
  val Sub_Add = Wire(Bool())

  when(io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_ADD) {
    Sub_Add := N
  }.otherwise {
    Sub_Add := Y
  }

  // ALU Adder
  val src_A      = io.IDU_2_EXU.bits.EXU_A
  val src_B      = io.IDU_2_EXU.bits.EXU_B

  val adder    = Wire(UInt(32.W))
  adder := src_A + Mux(Sub_Add, ~src_B, src_B) + Sub_Add

  // ALU BarrelShifter

  val shifter_result = MuxLookup(io.IDU_2_EXU.bits.EXUctr, 0.U)(Seq(
    EXUctr_TypeEnum.EXUctr_SLL -> (src_A << src_B(4, 0))(31, 0),
    EXUctr_TypeEnum.EXUctr_SRL -> (src_A >> src_B(4, 0))(31, 0),
    EXUctr_TypeEnum.EXUctr_SRA -> (src_A.asSInt >> src_B(4, 0))(31, 0)
  ))

  // other ALU outputs
  val Less = Wire(Bool())
  when(io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_Less_U){
    Less := src_A.asUInt < src_B.asUInt
  }.otherwise{
    Less := src_A.asSInt < src_B.asSInt
  }

  val Result = MuxLookup(io.IDU_2_EXU.bits.EXUctr, 0.U)(
    Seq(
      EXUctr_TypeEnum.EXUctr_ADD      -> adder,
      EXUctr_TypeEnum.EXUctr_SUB      -> adder,
      EXUctr_TypeEnum.EXUctr_Less_U   -> Cat(0.U(31.W), Less),
      EXUctr_TypeEnum.EXUctr_Less_S   -> Cat(0.U(31.W), Less),
      EXUctr_TypeEnum.EXUctr_A        -> src_A,
      EXUctr_TypeEnum.EXUctr_B        -> src_B,
      EXUctr_TypeEnum.EXUctr_SLL      -> shifter_result,
      EXUctr_TypeEnum.EXUctr_SRL      -> shifter_result,
      EXUctr_TypeEnum.EXUctr_SRA      -> shifter_result,
      EXUctr_TypeEnum.EXUctr_XOR      -> (src_A ^ src_B),
      EXUctr_TypeEnum.EXUctr_OR       -> (src_A | src_B),
      EXUctr_TypeEnum.EXUctr_AND      -> (src_A & src_B)
    )
  )

  val Jmp_Pc = MuxLookup(io.IDU_2_EXU.bits.Branch, io.IDU_2_EXU.bits.PC + io.IDU_2_EXU.bits.Imm)(Seq(
    Bran_TypeEnum.Bran_Jmpr -> (io.IDU_2_EXU.bits.EXU_A + io.IDU_2_EXU.bits.Imm),
    Bran_TypeEnum.Bran_Jcsr -> (io.IDU_2_EXU.bits.EXU_B)
  ))

  io.EXU_2_WBU.bits.Branch        := io.IDU_2_EXU.bits.Branch 
  io.EXU_2_WBU.bits.Jmp_Pc        := Jmp_Pc                  
  io.EXU_2_WBU.bits.MemtoReg      := io.IDU_2_EXU.bits.EXUctr === EXUctr_TypeEnum.EXUctr_LD 
  io.EXU_2_WBU.bits.csr_ctr       := io.IDU_2_EXU.bits.csr_ctr
  io.EXU_2_WBU.bits.CSR_waddr     := io.IDU_2_EXU.bits.Imm(11, 0)  
  io.EXU_2_WBU.bits.GPR_waddr     := io.IDU_2_EXU.bits.GPR_waddr
  io.EXU_2_WBU.bits.PC            := io.IDU_2_EXU.bits.PC     
  io.EXU_2_WBU.bits.CSR_rdata     := io.IDU_2_EXU.bits.EXU_B  
  io.EXU_2_WBU.bits.Result        := Result
  io.EXU_2_WBU.bits.Mem_rdata     := 0.U

  if(Config.Simulate){
    val Catch = Module(new ALU_catch)
    Catch.io.clock := clock
    Catch.io.valid := io.EXU_2_WBU.fire && !reset.asBool
    Catch.io.pc    := io.IDU_2_EXU.bits.PC
  }
}
