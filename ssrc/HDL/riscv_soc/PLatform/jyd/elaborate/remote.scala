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

class jyd_remote_cpu extends Module {
  val io = IO(new Bundle{
    val IROM = new IROM_bus
    val DRAM = new DRAM_bus
  })

  val IFU = Module(new jydIFU)
  val IDU = Module(new riscv_soc.cpu.IDU)
  val ALU = Module(new riscv_soc.cpu.ALU)
  val LSU = Module(new jydLSU)
  val WBU = Module(new riscv_soc.cpu.WBU)
  val REG = Module(new riscv_soc.cpu.REG)
  val PipelineCtrl = Module(new bus.PipelineCtrl)

  PipelineCtrl.io.GPR_read.valid := IDU.io.IDU_2_EXU.valid
  PipelineCtrl.io.GPR_read.bits := IDU.io.IDU_2_REG

  PipelineCtrl.io.IFU_out := IFU.io.IFU_2_IDU
  PipelineCtrl.io.IDU_in := IDU.io.IFU_2_IDU
  PipelineCtrl.io.ALU_in := ALU.io.IDU_2_EXU
  PipelineCtrl.io.LSU_in := LSU.io.IDU_2_EXU
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
      (to_LSU, LSU.io.IDU_2_EXU, LSU.io.EXU_2_WBU),
      (!to_LSU, ALU.io.IDU_2_EXU, ALU.io.EXU_2_WBU)
    ),
    PipelineCtrl.io.IDUCtrl
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
