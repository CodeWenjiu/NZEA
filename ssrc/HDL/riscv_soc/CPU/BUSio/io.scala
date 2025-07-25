package riscv_soc.bus

import chisel3._
import chisel3.util._

class BasicPipeBundle extends Bundle {
    val pc = UInt(32.W)
    val npc = UInt(32.W)

    val trap = new trap()
}

class WBU_output extends Bundle{
    val addr = Output(UInt(32.W))
}

class IFU_2_IDU extends Bundle {
    val inst = UInt(32.W)
    val pc   = UInt(32.W)
    val npc  = UInt(32.W)
}

class IDU_2_REG extends Bundle {
    val rs1_addr = UInt(5.W)
    val rs2_addr = UInt(5.W)
}

class REG_2_IDU extends Bundle {
    val rs1_val  = UInt(32.W)
    val rs2_val  = UInt(32.W)
}

class IDU_2_ISU extends Bundle {
    val basic = new BasicPipeBundle()

    val rs1_val  = UInt(32.W)
    val rs2_val  = UInt(32.W)

    val gpr_waddr = UInt(5.W)
    val imm = UInt(32.W)

    val is_ctrl = new IsCtrl()
    
    val al_ctrl = AlCtrl()
    val ls_ctrl = LsCtrl()

    val wb_ctrl = WbCtrl()
}

class ISU_2_REG extends Bundle {
    val csr_raddr = UInt(12.W)
}

class REG_2_ISU extends Bundle {
    val csr_rdata = UInt(32.W)
}

class ISU_2_ALU extends Bundle {
    val basic = new BasicPipeBundle()

    val SRCA = UInt(32.W)
    val SRCB = UInt(32.W)

    val al_ctrl = AlCtrl()
    val wb_ctrl = WbCtrl()

    val gpr_waddr = UInt(5.W)
    val csr_waddr = UInt(12.W)
}

class ISU_2_LSU extends Bundle {
    val basic = new BasicPipeBundle()

    val Ctrl = LsCtrl()
    val gpr_waddr = UInt(5.W)

    val addr = UInt(32.W)
    val data = UInt(32.W)
    val mask = UInt(4.W)
}

class EXU_2_WBU extends Bundle {
    val basic = new BasicPipeBundle()

    val Result   = UInt(32.W)
    val CSR_rdata = UInt(32.W)
    
    val gpr_waddr = UInt(5.W)
    val CSR_waddr= UInt(12.W)

    val wbCtrl = WbCtrl()
}

class BUS_WBU_2_REG extends Bundle{
    val inst_valid= Bool()
    val GPR_waddr = UInt(5.W)
    val GPR_wdata = UInt(32.W)
    val CSR_ctr   = CSR_TypeEnum()
    val CSR_waddra= UInt(12.W)
    val CSR_waddrb= UInt(12.W)
    val CSR_wdataa= UInt(32.W)
    val CSR_wdatab= UInt(32.W)
}

class WBU_2_REG extends Bundle {
    val basic = new BasicPipeBundle()

    val gpr_waddr = UInt(5.W)
    val gpr_wdata = UInt(32.W)

    val CSR_wen   = Bool()
    val CSR_waddr = UInt(12.W)
    val CSR_wdata = UInt(32.W)
}

class WB_Bypass extends Bundle {
    val gpr_waddr = UInt(5.W)
    val gpr_wdata = UInt(32.W)
}

class REG_2_WBU extends Bundle {
    val MTVEC = UInt(32.W)
}

object WbControlFlow extends ChiselEnum {
    val BPError,
        BPRight,
        Trap
        = Value
}

class WBU_2_IFU extends Bundle{
    val pc = UInt(32.W)
    val npc = UInt(32.W)
    val wb_ctrlflow = WbControlFlow()
}

class araddr extends Bundle{
    val addr = Output(UInt(32.W))
    val size = Output(UInt(3.W))
}

class rdata extends Bundle{
    val data = Input(UInt(32.W))
    val resp = Input(Bool())
}

class awaddr extends Bundle{
    val addr = Output(UInt(32.W))
    val size = Output(UInt(3.W))
}

class wdata extends Bundle{
    val data = UInt(32.W)
    val strb = UInt(4.W)
}

class bresp extends Bundle{
    val bresp = Input(Bool())
}

class Pipeline_ctrl extends Bundle {
  val stall = Output(Bool())
  val flush = Output(Bool())
}

object FlushableQueue {
  def apply[T <: Data](enq: ReadyValidIO[T], flush: Bool, entries: Int = 1,
      pipe: Boolean = false, flow: Boolean = false): DecoupledIO[T] = {
    if (entries == 0) {
      val deq = Wire(new DecoupledIO(enq.bits))
      deq.valid := enq.valid
      deq.bits := enq.bits
      enq.ready := deq.ready
      deq
    } else {
      require(entries > 0)
      val q = Module(new Queue(chiselTypeOf(enq.bits), entries, pipe, flow, hasFlush = true))
      q.io.enq <> enq
      q.io.flush.foreach(_ := flush)
      q.io.deq
    }
  }
}

object pipelineConnect {
    def apply[T <: Data, T2 <: Data](
        prevOut: DecoupledIO[T],
        thisIn: DecoupledIO[T], 
        ctrl: Pipeline_ctrl
    ) = {
        thisIn <> FlushableQueue(prevOut, ctrl.flush, pipe = true)
    }

    def apply[T <: Data, T2 <: Data](
        prevOut: DecoupledIO[T],
        thisIn: DecoupledIO[T], 
        thisOut: DecoupledIO[T2],
        ctrl: Pipeline_ctrl) = {

        prevOut.ready := thisIn.ready & ~ctrl.stall
        thisIn.bits := RegEnable(prevOut.bits, 0.U.asTypeOf(thisIn.bits), prevOut.fire)
        thisIn.valid := RegNext(
            MuxCase(
                thisIn.valid,
                Seq(
                    ctrl.flush   -> false.B,
                    prevOut.fire -> true.B,
                    thisOut.fire -> false.B
                )
            ),
            false.B
        )
    }

    def apply[T <: Data, T2 <: Data](
        prevOut: DecoupledIO[T],
        thisIn: DecoupledIO[T], 
        thisOut: DecoupledIO[T2]
    ): Unit = {
        val ctrl = Wire(new Pipeline_ctrl)
        ctrl.stall := false.B
        ctrl.flush := false.B

        apply(
            prevOut, 
            thisIn, 
            thisOut, 
            ctrl
        )
    }

    def apply[T <: Data, T2 <: Data](
        prevOut: DecoupledIO[T],
        thisIn: DecoupledIO[T], 
        thisOut: Seq[DecoupledIO[T2]],
        ctrl: Pipeline_ctrl) = {

        prevOut.ready := thisIn.ready & ~ctrl.stall
        thisIn.bits := RegEnable(prevOut.bits, prevOut.fire)
        thisIn.valid := RegNext(
            MuxCase(
                thisIn.valid,
                Seq(
                    ctrl.flush   -> false.B,
                    prevOut.fire -> true.B,
                    thisOut.map(_.fire).reduce(_ || _) -> false.B
                )
            ),
            false.B
        )
    }

    def apply[T <: Data](
        prevOut:  DecoupledIO[T],
        thisIn: Seq[(Bool, DecoupledIO[T], DecoupledIO[Data])], 
        ctrl:     Pipeline_ctrl) = {

        val branchActives = thisIn.map { case (cond, in, _) =>
            cond && in.ready
        }

        prevOut.ready := Mux(ctrl.stall, 
            false.B, 
            branchActives.reduce(_ || _) 
        )

        thisIn.foreach { case (cond, branchIn, branchOut) =>
            branchIn.bits := RegEnable(
                prevOut.bits,
                0.U.asTypeOf(prevOut.bits), 
                prevOut.fire && cond
            )

            branchIn.valid := RegNext(
                MuxCase(
                    branchIn.valid,
                    Seq(
                        ctrl.flush -> false.B,
                        (prevOut.fire && cond) -> true.B,
                        branchOut.fire -> false.B
                    )
                ), 
                false.B
            )
        }
    }

    def apply[T <: Data, T2 <: Data](
        prevOut: Seq[DecoupledIO[T]],
        thisIn: DecoupledIO[T],
        thisOut: DecoupledIO[T2],
        ctrl: Pipeline_ctrl): Unit = {
        
        val arb = Module(new Arbiter[T](thisIn.bits.cloneType, prevOut.size))

        arb.io.in <> prevOut

        apply(
            arb.io.out,
            thisIn,
            thisOut,
            ctrl
        )
    }

    // def mergeConnect[T <: Data](
    //     upstreams: Seq[DecoupledIO[T]],  // 所有上游输入接口
    //     thisIn: DecoupledIO[T], 
    //     thisOut: DecoupledIO[T2],
    //     ctrl: Pipeline_ctrl,              // 流水线控制信号
    // ): Unit = {
    //     val validMask = VecInit(upstreams.map(_.valid))
        
    //     val selectedIdx = PriorityEncoder(validMask)
    //     val selectedValid = Mux1H(UIntToOH(selectedIdx), upstreams.map(_.valid))
    //     val selectedFire  = Mux1H(UIntToOH(selectedIdx), upstreams.map(_.fire))
    //     val selectedData = Mux1H(UIntToOH(selectedIdx), upstreams.map(_.bits))

    //     // 阶段2：数据锁存与valid控制
    //     val dataReg = RegEnable(
    //         selectedData,
    //         selectedFire
    //     )
        
    //     val validReg = RegNext(
    //         MuxCase(
    //             selectedValid,
    //             Seq(
    //                 ctrl.flush -> false.B,
    //                 selectedFire -> true.B,
    //                 downstream.fire -> false.B
    //             )
    //         ),
    //         false.B
    //     )

    //     // 阶段3：信号连接
    //     downstream.valid := validReg
    //     downstream.bits := dataReg
        
    //     // 阶段4：反压反馈
    //     upstreams.zipWithIndex.foreach { case (up, i) =>
    //         up.ready := (i.U === selectedIdx) && 
    //                     downstream.ready &&
    //                     !ctrl.stall
    //     }
    // }
}