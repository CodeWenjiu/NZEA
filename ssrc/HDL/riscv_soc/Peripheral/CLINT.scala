package peripheral

import riscv_cpu._

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class CLINT(address: Seq[AddressSet], Freq: UInt)(implicit p: Parameters) extends LazyModule {
    val beatBytes = 4
    val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
            address       = address,
            executable    = false,
            supportsWrite = TransferSizes.none,
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0))
        ),
        beatBytes  = beatBytes)))
        
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
        val AXI = node.in(0)._1

        AXI.aw.ready := false.B
        AXI.w.ready := false.B
        AXI.b.valid := false.B
        AXI.b.bits.resp := 0.U

        AXI.r.bits.id := 1.U
        AXI.b.bits.id := 1.U
        AXI.r.bits.last := true.B

        val s_wait_valid :: s_wait_ready :: Nil = Enum(2)

        val state = RegInit(s_wait_valid)

        state := MuxLookup(state, s_wait_valid)(
            Seq(
                s_wait_valid -> Mux(AXI.ar.valid, s_wait_ready, s_wait_valid),
                s_wait_ready -> Mux(AXI.r.ready, s_wait_valid, s_wait_ready)
            )
        )

        AXI.ar.ready := (state === s_wait_valid)
        AXI.r.valid := (state === s_wait_ready)

        val mtime = RegInit(0.U(64.W))
        val m_counter = RegInit(0.U(Freq.getWidth.W))

        m_counter := m_counter + 1.U
        when(m_counter === Freq){//目前npc能够跑到800MHz
            m_counter := 0.U
            mtime := mtime + 1.U
        }

        val addr = RegEnable(AXI.ar.bits.addr(3, 0), AXI.ar.fire)

        AXI.r.bits.data := MuxLookup(addr, 0.U)(
            Seq(
                "h8".U -> mtime(31, 0),
                "hc".U -> mtime(63, 32)
            )
        )

        AXI.r.bits.resp := 0.U
    }
}