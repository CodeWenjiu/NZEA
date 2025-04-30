package riscv_soc.platform.jyd.remote
import riscv_soc.platform.jyd._

import chisel3._
import chisel3.util._

import config.Config
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import riscv_soc.bus.AXI4ToAPB

import riscv_soc.peripheral._
import scopt.platform
import riscv_soc.HasCoreModules
import riscv_soc.CoreConnect
import freechips.rocketchip.amba.axi4.AXI4Bundle
import riscv_soc.CPUAXI4BundleParameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import riscv_soc.bus
import _root_.peripheral.UART
import riscv_soc.cpu.EXUctr_Field

class Pipeline_catch extends BlackBox with HasBlackBoxInline{
  val io = IO(new Bundle {
      val clock = Input(Clock())
      val pipeline_flush = Input(Bool())
  })
  setInline("Pipeline_catch.v",
  """module Pipeline_catch(
  |  input clock,
  |  input pipeline_flush
  |);
  |import "DPI-C" function void Pipeline_catch();
  |always @(posedge clock) begin
  |    if(pipeline_flush) begin
  |        Pipeline_catch();
  |    end
  |end
  |endmodule
  """.stripMargin)
}

class PipelineCtrl extends Module {
    val io = IO(new Bundle {
        val GPR_read = Flipped(ValidIO((new bus.BUS_IDU_2_REG)))

        val IFU_out = Flipped(ValidIO(new bus.BUS_IFU_2_IDU))
        val IDU_in  = Flipped(ValidIO((new bus.BUS_IFU_2_IDU)))
        val ALU_in  = Flipped(ValidIO((new bus.BUS_IDU_2_EXU)))
        val AGU_in  = Flipped(ValidIO((new bus.BUS_IDU_2_EXU)))
        val WBU_in  = Flipped(ValidIO((new bus.BUS_EXU_2_WBU)))
        val Branch_msg = Flipped(ValidIO((new bus.BUS_WBU_2_IFU)))

        val IFUCtrl = new bus.Pipeline_ctrl
        val IDUCtrl = new bus.Pipeline_ctrl
        val AGUCtrl = new bus.Pipeline_ctrl
        val EXUCtrl = new bus.Pipeline_ctrl
    })

    def conflict(rs: UInt, rd: UInt) = (rs === rd)

    def conflict_gpr(rs: UInt, rd:UInt) = (conflict(rs, rd) && (rs =/= 0.U))
    def conflict_gpr_valid(rs: UInt) = 
        (conflict_gpr(rs, io.ALU_in.bits.GPR_waddr) & io.ALU_in.valid) ||
        (conflict_gpr(rs, io.WBU_in.bits.GPR_waddr) & io.WBU_in.valid)

    def is_gpr_RAW = io.GPR_read.valid && 
                     (conflict_gpr_valid(io.GPR_read.bits.GPR_Aaddr) ||
                     conflict_gpr_valid(io.GPR_read.bits.GPR_Baddr))

    def conflict_pc(target: UInt) =
        io.Branch_msg.valid && (target =/= io.Branch_msg.bits.Next_PC)

    def is_bp_error = MuxCase(conflict_pc(io.IFU_out.bits.PC), Seq(
        (io.ALU_in.valid -> conflict_pc(io.ALU_in.bits.PC)),
        (io.AGU_in.valid -> conflict_pc(io.AGU_in.bits.PC)),
        (io.IDU_in.valid -> conflict_pc(io.IDU_in.bits.PC)),
    ))

    def is_ls_hazard = io.AGU_in.valid

    io.IFUCtrl.flush := is_bp_error
    io.IFUCtrl.stall := false.B

    io.IDUCtrl.flush := is_bp_error
    io.IDUCtrl.stall := is_gpr_RAW | is_ls_hazard

    io.AGUCtrl.flush := is_bp_error
    io.AGUCtrl.stall := false.B

    io.EXUCtrl.flush := is_bp_error
    io.EXUCtrl.stall := false.B

    if(Config.Simulate) {
        val pipeline_catch = Module(new Pipeline_catch)
        pipeline_catch.io.clock := clock
        pipeline_catch.io.pipeline_flush := RegNext(io.IFUCtrl.flush)
    }
}

class jyd_remote_cpu extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM_bus
    val DRAM = new DRAM_bus
  })

  val IFU = Module(new jydIFU)
  val IDU = Module(new riscv_soc.cpu.IDU)
  val ALU = Module(new riscv_soc.cpu.ALU)
  val AGU = Module(new riscv_soc.cpu.AGU)
  val LSU = Module(new jydLSU)
  val WBU = Module(new riscv_soc.cpu.WBU)
  val REG = Module(new riscv_soc.cpu.REG)
  val PipelineCtrl = Module(new PipelineCtrl)

  PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
  PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

  PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
  PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
  PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
  PipelineCtrl.io.AGU_in := AGU.io.IDU_2_EXU
  PipelineCtrl.io.WBU_in := WBU.io.EXU_2_WBU

  PipelineCtrl.io.Branch_msg := WBU.io.WBU_2_IFU
  
  val to_LSU = WireDefault(false.B)
  to_LSU := IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_LD || IDU.io.IDU_2_EXU.bits.EXUctr === riscv_soc.bus.EXUctr_TypeEnum.EXUctr_ST

  IFU.io.Pipeline_ctrl := PipelineCtrl.io.IFUCtrl
  riscv_soc.bus.pipelineConnect(
    IFU.io.IFU_2_IDU,
    IDU.io.IFU_2_IDU,
    IDU.io.IDU_2_EXU,
    PipelineCtrl.io.IFUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    IDU.io.IDU_2_EXU,
    Seq(
      (to_LSU, AGU.io.IDU_2_EXU, AGU.io.AGU_2_LSU),
      (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
    ),
    PipelineCtrl.io.IDUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    AGU.io.AGU_2_LSU,
    LSU.io.AGU_2_LSU,
    LSU.io.EXU_2_WBU,
    PipelineCtrl.io.AGUCtrl
  )

  riscv_soc.bus.pipelineConnect(
    Seq(
      (LSU.io.EXU_2_WBU),
      (ALU.io.EXU_2_WBU)
    ),
    WBU.io.EXU_2_WBU,
    WBU.io.WBU_2_IFU,
    PipelineCtrl.io.EXUCtrl
  )

  WBU.io.WBU_2_IFU.ready := true.B
  REG.io.REG_2_IDU <> IDU.io.REG_2_IDU
  IDU.io.IDU_2_REG <> REG.io.IDU_2_REG
  IFU.io.WBU_2_IFU <> WBU.io.WBU_2_IFU.bits
  WBU.io.WBU_2_REG <> REG.io.WBU_2_REG
  LSU.io.flush := PipelineCtrl.io.EXUCtrl.flush

  io.IROM <> IFU.io.IROM
  io.DRAM <> LSU.io.DRAM
}

import org.chipsalliance.cde.config.{Parameters, Config}
import freechips.rocketchip.system._
import riscv_soc.bus._

class core extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM_bus
    val DRAM = new DRAM_bus
  })

  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val mdut = Module(new jyd_remote_cpu)

  io.IROM <> mdut.io.IROM
  io.DRAM <> mdut.io.DRAM
}

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
        val clock = Input(Clock())
        val valid = Input(Bool())
        val addr  = Input(UInt(32.W))
        val wen   = Input(Bool())
        val mask  = Input(UInt(2.W))
        val wdata = Input(UInt(32.W))
        val rdata = Output(UInt(32.W))
    })
    val code = 
    s"""
    |module DRAM(
    |    input clock,
    |    input valid,
    |    input [31:0] addr,
    |    input wen,
    |    input [1:0] mask,
    |    input [31:0] wdata,
    |    output [31:0] rdata
    |);
    |
    |   import "DPI-C" function void DRAM_read(input bit [31:0] addr, output bit [31:0] data);
    |   import "DPI-C" function void DRAM_write(input bit [31:0] addr, input bit [1:0] mask, input bit [31:0] data);
    |   always @(posedge clock) begin
    |       if(wen) begin
    |           DRAM_write(addr, mask, wdata);
    |       end
    |       else begin
    |           DRAM_read(addr, rdata);
    |       end
    |   end
    |
    |endmodule
    """

    setInline("DRAM.v", code.stripMargin)
}

class top extends Module {
  implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

  val mdut = Module(new jyd_remote_cpu)
  val irom = Module(new IROM)
  val dram = Module(new DRAM)

  irom.io.clock := clock
  irom.io.addr := mdut.io.IROM.addr
  mdut.io.IROM.data := irom.io.data

  dram.io.clock := clock  
  dram.io.valid := true.B
  dram.io.addr := mdut.io.DRAM.addr
  dram.io.wen := mdut.io.DRAM.wen
  dram.io.mask := mdut.io.DRAM.mask
  dram.io.wdata := mdut.io.DRAM.wdata
  mdut.io.DRAM.rdata := dram.io.rdata
}
