package riscv_soc.platform.jyd.onboard
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
import riscv_soc.platform.vivado.AXI_CDC

class axi_clock_converter extends AXI_CDC
class axi_clock_converter_1 extends AXI_CDC

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

class core_basic(idBits: Int, io_split: Boolean)(implicit p: Parameters) extends LazyModule {
    ElaborationArtefacts.add("graphml", graphML)
    val LazyIFU = LazyModule(new frontend.IFU(idBits = idBits - 1))
    val LazyLSU = LazyModule(new backend.LSU(idBits = idBits - 1))

    val xbar = AXI4Xbar(
        maxFlightPerId = 1, 
        awQueueDepth = 1
    )
    xbar := LazyLSU.masterNode

    val irom_addrSet = AddressSet.misaligned(0x80000000L, 0x4000)
    val dram_addrSet = AddressSet.misaligned(0x80100000L, 0x40000)
    val peripheral_addrSet = AddressSet.misaligned(0x80200000L, 0x10000)

    val ls_addrSet = if (io_split) 
        dram_addrSet
    else
        dram_addrSet ++ peripheral_addrSet

    val if_node = AXI4SlaveNode(
        Seq(
            AXI4SlavePortParameters(
                Seq(
                AXI4SlaveParameters(
                    address       = irom_addrSet,
                    executable    = true,
                    supportsRead  = TransferSizes(1, 4),
                    interleavedId = Some(0)
                )
                ),
                
                beatBytes  = 4
            )
        )
    )
    if (Config.Simulate) {
        if_node := AXI4Delayer(0.1) := LazyIFU.masterNode
    } else {
        if_node := LazyIFU.masterNode
    }

    val ls_node = AXI4SlaveNode(
        Seq(
            AXI4SlavePortParameters(
                Seq(
                AXI4SlaveParameters(
                    address       = ls_addrSet,
                    executable    = true,
                    supportsRead  = TransferSizes(1, 4),
                    interleavedId = Some(0)
                )
                ),
                
                beatBytes  = 4
            )
        )
    )
    if (Config.Simulate) {
        ls_node := AXI4Delayer(0.1) := xbar
    } else {
        ls_node := xbar
    }

    val peripheral_node = if (io_split) {
        val node = AXI4SlaveNode(
            Seq(
                AXI4SlavePortParameters(
                    Seq(
                    AXI4SlaveParameters(
                        address       = peripheral_addrSet,
                        executable    = true,
                        supportsRead  = TransferSizes(1, 4),
                        interleavedId = Some(0)
                    )
                    ),
                    
                    beatBytes  = 4
                )
            )
        )
        node := xbar
        node
    } else {
        null
    }
    
    override lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
        val IFU = LazyIFU.module
        val IDU = Module(new frontend.IDU)
        val ISU = Module(new frontend.ISU)

        val ALU = Module(new backend.ALU)
        val LSU = LazyLSU.module
        val WBU = Module(new backend.WBU)
        
        val REG = Module(new REG)
        val PipelineCtrl = Module(new PipelineCtrl)

        CoreConnect(this)

        val if_axi = IO(new AXI4Bundle(CPUAXI4BundleParameters()))
        if_axi <> if_node.in(0)._1

        val ls_axi = IO(new AXI4Bundle(CPUAXI4BundleParameters()))
        ls_axi <> ls_node.in(0)._1
        
        val peripheral_axi = if (peripheral_node != null) {
            val axi = IO(new AXI4Bundle(CPUAXI4BundleParameters()))
            axi <> peripheral_node.in(0)._1
            axi
        } else {
            null
        }
    }
}

class top extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new core_basic(idBits = ChipLinkParam.idBits, false))
    val irom = Module(new IROM())
    val dram = Module(new DRAM())
    
    val mdut = Module(dut.module)
    val irom_wrapper = Module(new IROM_WrapFromAXI())
    irom_wrapper.io.axi <> mdut.if_axi

    val dram_wrapper = Module(new DRAM_WrapFromAXI())
    dram_wrapper.io.axi <> mdut.ls_axi

    irom.io <> irom_wrapper.io.irom

    dram.io <> dram_wrapper.io.dram

    mdut.dontTouchPorts()
}

class core(
    is_directly_axi: Boolean = true
) extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    val dut = LazyModule(new core_basic(idBits = ChipLinkParam.idBits, is_directly_axi))
    val mem_clk = IO(Input(Clock()))
    val periph_clk = IO(Input(Clock()))
    
    val mdut = Module(dut.module)

    val irom_cdc = Module(new axi_clock_converter())
    irom_cdc.io.s.connect(mdut.if_axi)
    irom_cdc.io.s_axi_aclk := clock
    irom_cdc.io.s_axi_aresetn := !reset.asBool
    irom_cdc.io.m_axi_aclk := mem_clk
    irom_cdc.io.m_axi_aresetn := !reset.asBool

    val dram_cdc = Module(new axi_clock_converter())
    dram_cdc.io.s.connect(mdut.ls_axi)
    dram_cdc.io.s_axi_aclk := clock
    dram_cdc.io.s_axi_aresetn := !reset.asBool
    dram_cdc.io.m_axi_aclk := mem_clk
    dram_cdc.io.m_axi_aresetn := !reset.asBool

    if(is_directly_axi) {
        val irom = IO(chiselTypeOf(irom_cdc.io.m))
        val dram = IO(chiselTypeOf(dram_cdc.io.m))
        irom <> irom_cdc.io.m
        dram <> dram_cdc.io.m

        val peripheral_cdc = Module(new axi_clock_converter_1())
        peripheral_cdc.io.s.connect(mdut.peripheral_axi)
        peripheral_cdc.io.s_axi_aclk := clock
        peripheral_cdc.io.s_axi_aresetn := !reset.asBool
        peripheral_cdc.io.m_axi_aclk := periph_clk
        peripheral_cdc.io.m_axi_aresetn := !reset.asBool

        val perh_wrapper = Module(new DRAM_WrapFromAXI())
        perh_wrapper.clock := periph_clk
        perh_wrapper.io.axi <> DontCare
        peripheral_cdc.io.m.connect(perh_wrapper.io.axi)
        
        val peripheral = IO(Flipped(new DRAM_bus()))
        peripheral <> perh_wrapper.io.dram
    } else {
        val irom = IO(Flipped(new IROM_bus()))
        val dram = IO(Flipped(new DRAM_bus()))

        val irom_wrapper = Module(new IROM_WrapFromAXI())
        irom_wrapper.io.axi <> DontCare
        irom_wrapper.clock := mem_clk
        irom_cdc.io.m.connect(irom_wrapper.io.axi)

        irom <> irom_wrapper.io.irom

        val dram_wrapper = Module(new DRAM_WrapFromAXI())
        dram_wrapper.clock := mem_clk
        dram_wrapper.io.axi <> DontCare
        dram_cdc.io.m.connect(dram_wrapper.io.axi)
        
        dram <> dram_wrapper.io.dram
    }

    mdut.dontTouchPorts()
}
