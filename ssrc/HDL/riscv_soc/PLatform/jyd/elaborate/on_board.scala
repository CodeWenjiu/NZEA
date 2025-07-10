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

object CPUAXI4BundleParameters {
  def apply() = AXI4BundleParameters(
    addrBits = 32,
    dataBits = 32,
    idBits = ChipLinkParam.idBits
  )
}

class core(idBits: Int)(implicit p: Parameters) extends LazyModule {
    ElaborationArtefacts.add("graphml", graphML)
    val LazyIFU = LazyModule(new frontend.IFU(idBits = idBits - 1))
    val LazyLSU = LazyModule(new backend.LSU(idBits = idBits - 1))

    val xbar = AXI4Xbar(maxFlightPerId = 1, awQueueDepth = 1)
    xbar := LazyIFU.masterNode
    xbar := LazyLSU.masterNode
    // val lirom = LazyModule(
    //     new IROM_AXI_Wrap(AddressSet.misaligned(0x80000000L, 0x4000))
    // )

    // val ldram = LazyModule(
    //     new DRAM_Wrap(AddressSet.misaligned(0x80100000L, 0x40000) ++ 
    //                   AddressSet.misaligned(0x80200000L, 0x10000))
    // )

    // lirom.node := xbar
    // ldram.node := xbar

    // val if_node = AXI4SlaveNode(
    //     Seq(
    //     AXI4SlavePortParameters(
    //         Seq(
    //         AXI4SlaveParameters(
    //             address       = AddressSet.misaligned(0x80000000L, 0x4000),
    //             executable    = true,
    //             supportsRead  = TransferSizes(1, 4),
    //             interleavedId = Some(0)
    //         )
    //         ),
            
    //         beatBytes  = 4
    //     )
    //     )
    // )
    // if_node := xbar
    
    override lazy val module = new Impl
    class Impl extends LazyModuleImp(this) with HasCoreModules with DontTouch {
        // val io = IO(new Bundle {
        //     val master = AXI4Bundle(CPUAXI4BundleParameters())
        // })

        // io.master <> if_node.in(0)._1

        // val dram = IO(chiselTypeOf(ldram.module.io))
        // dram <> ldram.module.io
        val IFU = LazyIFU.module
        val IDU = Module(new frontend.IDU)
        val ISU = Module(new frontend.ISU)

        val ALU = Module(new backend.ALU)
        val LSU = LazyLSU.module
        val WBU = Module(new backend.WBU)
        
        val REG = Module(new REG)
        val PipelineCtrl = Module(new PipelineCtrl)

        CoreConnect(this)
    }
}

class run(idBits: Int)(implicit p: Parameters) extends core(idBits) {
    val ldram = LazyModule(
        new DRAM_Wrap(AddressSet.misaligned(0x80100000L, 0x40000) ++ 
                      AddressSet.misaligned(0x80200000L, 0x10000))
    )

    ldram.node := xbar
    val if_node = AXI4SlaveNode(
        Seq(
        AXI4SlavePortParameters(
            Seq(
            AXI4SlaveParameters(
                address       = AddressSet.misaligned(0x80000000L, 0x4000),
                executable    = true,
                supportsRead  = TransferSizes(1, 4),
                interleavedId = Some(0)
            )
            ),
            
            beatBytes  = 4
        )
        )
    )
    if_node := xbar

    class RunImpl extends super.Impl {
        val io = IO(new Bundle {
            val master = AXI4Bundle(CPUAXI4BundleParameters())
        })
        io.master <> if_node.in(0)._1

        val dram = IO(chiselTypeOf(ldram.module.io))
        dram <> ldram.module.io
    }

    override lazy val module = new RunImpl
}

class sim(idBits: Int)(implicit p: Parameters) extends core(idBits) {
    val lirom = LazyModule(
        new IROM_Wrap2AXI(AddressSet.misaligned(0x80000000L, 0x4000))
    )
    val ldram = LazyModule(
        new DRAM_Wrap(AddressSet.misaligned(0x80100000L, 0x40000) ++ 
                      AddressSet.misaligned(0x80200000L, 0x10000))
    )

    lirom.node := xbar
    ldram.node := xbar

    class RunImpl extends super.Impl {
        val irom = IO(chiselTypeOf(lirom.module.io))
        irom <> lirom.module.io

        val dram = IO(chiselTypeOf(ldram.module.io))
        dram <> ldram.module.io
    }

    override lazy val module = new RunImpl
}

class top extends Module {
    implicit val config: Parameters = new Config(new Edge32BitConfig ++ new DefaultRV32Config)

    if (Config.Simulate) {
        val dut = LazyModule(new sim(idBits = ChipLinkParam.idBits))
        val irom = Module(new IROM())
        val dram = Module(new DRAM())
        
        val mdut = Module(dut.module)

        irom.io <> mdut.irom

        dram.io <> mdut.dram

        mdut.dontTouchPorts()
    } else {
        val dut = LazyModule(new run(idBits = ChipLinkParam.idBits))
        val mdut = Module(dut.module)

        val dram = IO(chiselTypeOf(dut.ldram.module.io))
        dram <> mdut.dram

        val if_axi = IO(chiselTypeOf(dut.if_node.in(0)._1))
        if_axi <> mdut.io.master

        mdut.dontTouchPorts()
    }
}
