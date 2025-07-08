package riscv_soc.platform.jyd.remote
import riscv_soc.platform.jyd._

import chisel3._
import chisel3.util._
import chisel3.util._

import config._
import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.system._

import riscv_soc.peripheral._
import scopt.platform
import riscv_soc.HasCoreModules
import riscv_soc.CoreConnect
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.cpu._
import peripheral._
import riscv_soc.bus._

class top extends Module {

  val BPU = Module(new riscv_soc.cpu.frontend.BPU)
  val IFU = Module(new jydIFU)
  val IDU = Module(new riscv_soc.cpu.frontend.IDU)
  val ISU = Module(new riscv_soc.cpu.frontend.ISU)

  val ALU = Module(new riscv_soc.cpu.backend.ALU)
  val LSU = Module(new jydLSU)
  val WBU = Module(new riscv_soc.cpu.backend.WBU)
  
  val REG = Module(new riscv_soc.cpu.REG)
  val PipelineCtrl = Module(new riscv_soc.bus.PipelineCtrl)

  PipelineCtrl.io.GPR_READMSG.valid := IDU.io.IDU_2_ISU.valid
  PipelineCtrl.io.GPR_READMSG.bits := IDU.io.IDU_GPR_READMSG

  PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
  PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
  PipelineCtrl.io.ISU_in := ISU.io.IDU_2_ISU
  PipelineCtrl.io.ALU_in := ALU.io.ISU_2_ALU
  PipelineCtrl.io.LSU_in := LSU.io.ISU_2_LSU
  PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

  PipelineCtrl.io.WBU_out := WBU.io.WBU_2_BPU

  IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl

  riscv_soc.bus.pipelineConnect(
    BPU.io.BPU_2_IFU,
    IFU.io.BPU_2_IFU,
    IFU.io.IFU_2_IDU,
    PipelineCtrl.io.IFUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    IFU.io.IFU_2_IDU,
    IDU.io.IFU_2_IDU,
    IDU.io.IDU_2_ISU,
    PipelineCtrl.io.IFUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    IDU.io.IDU_2_ISU,
    ISU.io.IDU_2_ISU,
    Seq(ISU.io.ISU_2_ALU, ISU.io.ISU_2_LSU),
    PipelineCtrl.io.IDUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    ISU.io.ISU_2_ALU,
    ALU.io.ISU_2_ALU,
    ALU.io.ALU_2_WBU,
    PipelineCtrl.io.ISU_2_ALUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    ISU.io.ISU_2_LSU,
    LSU.io.ISU_2_LSU,
    LSU.io.LSU_2_WBU,
    PipelineCtrl.io.ISU_2_LSUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    Seq(
      (LSU.io.LSU_2_WBU),
      (ALU.io.ALU_2_WBU)
    ),
    WBU.io.EXU_2_WBU,
    WBU.io.WBU_2_BPU,
    PipelineCtrl.io.EXUCtrl
  )

  REG.io.IDU_2_REG <> IDU.io.IDU_2_REG
  REG.io.REG_2_IDU <> IDU.io.REG_2_IDU

    REG.io.ISU_2_REG <> ISU.io.ISU_2_REG
    REG.io.REG_2_ISU <> ISU.io.REG_2_ISU
    
  REG.io.WBU_2_REG <> WBU.io.WBU_2_REG
  REG.io.REG_2_WBU <> WBU.io.REG_2_WBU

  LSU.io.is_flush := PipelineCtrl.io.EXUCtrl.flush

    BPU.io.WBU_2_BPU <> WBU.io.WBU_2_BPU

    IDU.io.WB_Bypass <> WBU.io.WB_Bypass

    if (Config.Simulate) {
        val irom = Module(new IROM())
        val dram = Module(new DRAM())

        irom.io.clock := clock

        irom.io.addr := IFU.io.IROM.addr
        IFU.io.IROM.data := irom.io.data
        dram.io.addr := LSU.io.DRAM.addr
        dram.io.wen := LSU.io.DRAM.wen
        dram.io.mask := LSU.io.DRAM.mask
        dram.io.wdata := LSU.io.DRAM.wdata
        LSU.io.DRAM.rdata := dram.io.rdata
        // irom.io <> IFU.io.IROM
        // dram.io <> LSU.io.DRAM
    } else {
        val irom = IO(Flipped(new IROM_bus))
        val dram = IO(Flipped(new DRAM_bus))

        irom <> IFU.io.IROM
        dram <> LSU.io.DRAM
    }
}

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

// class core extends Module {
//   val io = IO(new Bundle{
//     val IROM = new IROM_bus
//     val DRAM = new DRAM_bus
//   })

//   implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

//   val mdut = Module(new jyd_remote_cpu)

//   io.IROM <> mdut.io.IROM
//   io.DRAM <> mdut.io.DRAM
// }

class IROM extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val addr = Input(UInt(32.W))
        val data = Output(UInt(32.W))
    })
    val code = 
    s"""
    |module IROM(
    |    input clock,
    |    input [31:0] addr,
    |    output [31:0] data
    |);
    |
    |   import "DPI-C" function void IROM_read(input bit [31:0] addr, output bit [31:0] data);
    |   always @(*) begin
    |       IROM_read(addr, data);
    |   end
    |
    |endmodule
    """

    setInline("IROM.v", code.stripMargin)
}

class DRAM extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val addr  = Input(UInt(32.W))
        val wen   = Input(Bool())
        val mask  = Input(UInt(2.W))
        val wdata = Input(UInt(32.W))
        val rdata = Output(UInt(32.W))
    })
    val code = 
    s"""
    |module DRAM(
    |    input [31:0] addr,
    |    input wen,
    |    input [1:0] mask,
    |    input [31:0] wdata,
    |    output reg [31:0] rdata
    |);
    |
    |   import "DPI-C" function void DRAM_read(input bit [31:0] addr, input bit [1:0] mask, output bit [31:0] data);
    |   import "DPI-C" function void DRAM_write(input bit [31:0] addr, input bit [1:0] mask, input bit [31:0] data);
    |   always @(*) begin
    |       if(wen) begin
    |           DRAM_write(addr, mask, wdata);
    |           rdata = 0; 
    |       end
    |       else begin
    |           DRAM_read(addr, mask, rdata);
    |       end
    |   end
    |
    |endmodule
    """

    setInline("DRAM.v", code.stripMargin)
}
