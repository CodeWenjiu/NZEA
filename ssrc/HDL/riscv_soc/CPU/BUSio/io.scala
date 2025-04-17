package riscv_soc.bus

import chisel3._
import chisel3.util._

class WBU_output extends Bundle{
    val addr = Output(UInt(32.W))
}

class BUS_IFU_2_IDU extends Bundle{
    val data = UInt(32.W)
    val PC         = UInt(32.W)
}

class BUS_IDU_2_IFU extends Bundle{
    val hazard = Bool()
}

class BUS_IFU_2_REG extends Bundle{
}

class BUS_REG_2_IDU extends Bundle{
    val CSR_rdata  = UInt(32.W)
    val GPR_Adata  = UInt(32.W)
    val GPR_Bdata  = UInt(32.W)
}

class BUS_IDU_2_EXU extends Bundle{
    val Branch   = Bran_TypeEnum()
    val MemOp    = MemOp_TypeEnum()
    val EXU_A    = UInt(32.W)
    val EXU_B    = UInt(32.W)
    val EXUctr   = EXUctr_TypeEnum()
    val csr_ctr  = CSR_TypeEnum() 
    val Imm      = UInt(32.W)
    val GPR_waddr = UInt(4.W)
    val PC       = UInt(32.W)
}

class BUS_AGU_2_LSU extends Bundle{
    val MemOp    = MemOp_TypeEnum()
    val MemAddr  = UInt(32.W)
    val MemData  = UInt(32.W)
}

class BUS_IDU_2_REG extends Bundle{
    val GPR_Aaddr  = UInt(5.W)
    val GPR_Baddr  = UInt(5.W)
    val CSR_raddr   = UInt(12.W)
}

class BUS_REG_2_EXU extends Bundle{
}

class BUS_EXU_2_WBU extends Bundle{
    val Branch   = Bran_TypeEnum()
    val Jmp_Pc   = UInt(32.W)
    val MemtoReg = Bool()
    val csr_ctr  = CSR_TypeEnum()
    val CSR_waddr= UInt(12.W)
    val GPR_waddr = UInt(4.W)
    val PC       = UInt(32.W)
    val CSR_rdata = UInt(32.W)
    val Result   = UInt(32.W)
    val Mem_rdata  = Bits(32.W)
}

class BUS_WBU_2_REG extends Bundle{
    val inst_valid= Bool()
    val GPR_waddr = UInt(4.W)
    val GPR_wdata = UInt(32.W)
    val CSR_ctr   = CSR_TypeEnum()
    val CSR_waddra= UInt(12.W)
    val CSR_waddrb= UInt(12.W)
    val CSR_wdataa= UInt(32.W)
    val CSR_wdatab= UInt(32.W)
}

class BUS_WBU_2_IFU extends Bundle{
    val Next_PC = UInt(32.W)
}

trait Bus_default_value {
    def setDefault(): Unit
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

class AXI_Master extends Bundle with Bus_default_value{
    val araddr = Decoupled(new araddr)
    val rdata = Flipped(Decoupled(new rdata))
    val awaddr = Decoupled(new awaddr)
    val wdata = Decoupled(Output(new wdata))
    val bresp  = Flipped(Decoupled(new bresp))

    def setDefault(): Unit = {
        araddr.ready := false.B
        rdata.valid := false.B
        awaddr.ready := false.B
        wdata.ready := false.B
        bresp.valid := false.B
    }
}

class AXI_Slave extends Bundle{
    val araddr = Flipped(Decoupled(new araddr))
    val rdata = Decoupled(new rdata)
    val awaddr = Flipped(Decoupled(new awaddr))
    val wdata = Flipped(Decoupled(new wdata))
    val bresp  = Decoupled(new bresp)
}

class FIX_AXI_BUS_Master extends Bundle{
  val awready = Input(Bool())
  val awvalid = Output(Bool())
  val awaddr  = Output(UInt(32.W))
  val awid    = Output(UInt(4.W))
  val awlen   = Output(UInt(8.W))
  val awsize  = Output(UInt(3.W))
  val awburst = Output(UInt(2.W))

  val wready = Input(Bool())
  val wvalid = Output(Bool())
  val wdata  = Output(UInt(32.W))
  val wstrb  = Output(UInt(4.W))
  val wlast  = Output(Bool())

  val bready = Output(Bool())
  val bvalid = Input(Bool())
  val bresp  = Input(UInt(2.W))
  val bid    = Input(UInt(4.W))

  val arready = Input(Bool())
  val arvalid = Output(Bool())
  val araddr  = Output(UInt(32.W))
  val arid    = Output(UInt(4.W))
  val arlen   = Output(UInt(8.W))
  val arsize  = Output(UInt(3.W))
  val arburst = Output(UInt(2.W))

  val rready = Output(Bool())
  val rvalid = Input(Bool())
  val rresp  = Input(UInt(2.W))
  val rdata  = Input(UInt(32.W))
  val rlast  = Input(Bool())
  val rid    = Input(UInt(4.W))
}

class FIX_AXI_BUS_Slave extends Bundle{
  val awready = Output(Bool())
  val awvalid = Input(Bool())
  val awaddr  = Input(UInt(32.W))
  val awid    = Input(UInt(4.W))
  val awlen   = Input(UInt(8.W))
  val awsize  = Input(UInt(3.W))
  val awburst = Input(UInt(2.W))

  val wready = Output(Bool())
  val wvalid = Input(Bool())
  val wdata  = Input(UInt(32.W))
  val wstrb  = Input(UInt(4.W))
  val wlast  = Input(Bool())

  val bready = Input(Bool())
  val bvalid = Output(Bool())
  val bresp  = Output(UInt(2.W))
  val bid    = Output(UInt(4.W))

  val arready = Output(Bool())
  val arvalid = Input(Bool())
  val araddr  = Input(UInt(32.W))
  val arid    = Input(UInt(4.W))
  val arlen   = Input(UInt(8.W))
  val arsize  = Input(UInt(3.W))
  val arburst = Input(UInt(2.W))

  val rready = Input(Bool())
  val rvalid = Output(Bool())
  val rresp  = Output(UInt(2.W))
  val rdata  = Output(UInt(32.W))
  val rlast  = Output(Bool())
  val rid    = Output(UInt(4.W))
}

class Pipeline_ctrl extends Bundle {
  val stall = Output(Bool())
  val flush = Output(Bool())
}

object pipelineConnect {
    def apply[T <: Data, T2 <: Data](
        prevOut: DecoupledIO[T],
        thisIn: DecoupledIO[T], 
        thisOut: DecoupledIO[T2],
        ctrl: Pipeline_ctrl) = {

        prevOut.ready := thisIn.ready & ~ctrl.stall
        thisIn.bits := RegEnable(prevOut.bits, 0.U.asTypeOf(prevOut.bits), prevOut.fire)
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