package riscv_soc.bus

import chisel3._
import chisel3.util._

import chisel3.util.BitPat

import freechips.rocketchip.subsystem._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.system._

// total instruction num: 35

object ChipLinkParam {
  // Must have a cacheable address sapce.
  val mem  = AddressSet(0xc0000000L, 0x40000000L - 1)
  val mmio = AddressSet(0x40000000L, 0x40000000L - 1)
  val allSpace = Seq(mem, mmio)
  val idBits = 3
}

object Special_instTypeEnum extends ChiselEnum {
  val None,
      fence_I
      = Value
}

object IsLogic extends ChiselEnum {
  val EQ = Value((1 << 0).U)
  val NE = Value((1 << 1).U)

  val LT = Value((1 << 2).U)
  val GE = Value((1 << 3).U)

  val LTU = Value((1 << 4).U)
  val GEU = Value((1 << 5).U)

  val SLTI = Value((1 << 6).U)
  val SLTIU = Value((1 << 7).U)
}

object Inst_Type extends ChiselEnum {
  val 
      AL,
      LS

      = Value
}

object SRCA extends ChiselEnum {
  val 
      RS1,
      PC,
      ZERO,
      CSR

      = Value
}

object SRCB extends ChiselEnum {
  val 
      RS1,
      RS2,
      IMM,
      LogicBranch,
      LogicSet

      = Value
}

class IsCtrl extends Bundle {
  val inst_Type = Inst_Type()
  val isLogic = IsLogic()
  val srca = SRCA()
  val srcb = SRCB()
}

object AlCtrl extends ChiselEnum {
  val WTF = Value(0.U) // WTF, should not be used

  val B   = Value((1 << 0).U) // Branch

  val ADD = Value((1 << 1).U)
  val SUB = Value((1 << 2).U)

  val AND = Value((1 << 3).U)
  val OR  = Value((1 << 4).U)
  val XOR = Value((1 << 5).U)
  
  val SLL = Value((1 << 6).U)
  val SRL = Value((1 << 7).U)
  val SRA = Value((1 << 8).U)
}

object LsCtrl extends ChiselEnum {
  val LB,
      LH,
      LW,
      LBU,
      LHU,

      SB,
      SH,
      SW
      = Value
}

object CSR_TypeEnum extends ChiselEnum{
  val CSR_N   ,    // 非csr读写指令
      CSR_R1W0,    // 不读写一， 目前只有 mret 符合
      CSR_R1W1,    // 读写一
      CSR_R1W2     // 读一写二， 目前只有 ecall 符合
      = Value
}

object signal_value {
  def Y = true.B
  def N = false.B
}

object bus_state extends ChiselEnum{
  val s_wait_valid,
      s_wait_ready,
      s_busy,
      s_pipeline
      = Value
}

// new enums
object WbCtrl extends ChiselEnum {
  val Write_GPR,
      Jump,
      Csr
      = Value
}

object Trap_type extends ChiselEnum {
  val Instruction_address_misaligned = Value(0.U)
  val Instruction_Illegal = Value(2.U)
  val Ebreak = Value(3.U)
  val EcallM = Value(11.U)

  val Mret = Value // None a trap actually, but it's really convenient to put it here
  val None = Value
}
