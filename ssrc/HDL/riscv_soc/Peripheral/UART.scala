package peripheral

import riscv_cpu._

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import org.chipsalliance.diplomacy.lazymodule._

class UART_bridge extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val valid = Input(Bool())
        val data = Input(UInt(8.W))
    })
    setInline("UART_bridge.v",
    """module UART_bridge(
      |  input clock,
      |  input valid,
      |  input [7:0] data
      |);
      |  import "DPI-C" function void Uart_putc(input bit [7:0] c);
      |  
      |  always @(posedge clock) begin
      |    if(valid) begin
      |      Uart_putc(data);
      |    end
      |  end
      |
      |endmodule
    """.stripMargin)
}

class UART(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
    val beatBytes = 4
    val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
            address       = address,
            executable    = false,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0))
        ),
        beatBytes  = beatBytes)))
        
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {

        val AXI = node.in(0)._1

        AXI.ar.ready := false.B
        AXI.r.valid  := false.B
        AXI.r.bits.resp := 0.U
        AXI.r.bits.data := 0.U

        AXI.r.bits.id := RegEnable(AXI.ar.bits.id, AXI.ar.fire)
        AXI.b.bits.id := RegEnable(AXI.aw.bits.id, AXI.aw.fire)
        AXI.r.bits.last := true.B

        val s_wait_addr :: s_wait_data :: s_wait_resp :: Nil = Enum(3)

        val state_w = RegInit(s_wait_addr)
        val state_cache = RegInit(s_wait_addr)
        state_cache := state_w
        
        state_w := MuxLookup(state_w, s_wait_addr)(
            Seq(
                s_wait_addr -> Mux(AXI.aw.fire, s_wait_data, s_wait_addr),
                s_wait_data -> Mux(AXI.w.fire,  s_wait_resp, s_wait_data),
                s_wait_resp -> Mux(AXI.b.fire,  s_wait_addr, s_wait_resp)
            )
        )

        AXI.aw.ready := state_w === s_wait_addr
        AXI.w.ready  := state_w === s_wait_data
        AXI.b.valid  := state_w === s_wait_resp
        AXI.b.bits.resp   := 0.U

        val Uart_bridge = Module(new UART_bridge)

        when(state_cache =/= s_wait_resp && state_w === s_wait_resp){
            Uart_bridge.io.valid := true.B
        }.otherwise{
            Uart_bridge.io.valid := false.B
        }

        Uart_bridge.io.clock := clock
        Uart_bridge.io.data := RegEnable(AXI.w.bits.data, AXI.w.valid && AXI.w.ready)
    }
}
