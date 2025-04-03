package ram

import riscv_cpu._

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import org.chipsalliance.diplomacy.lazymodule._

class sram_bridge extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle{
        val clock = Input(Clock())
        val read  = Input(Bool())
        val r_addr  = Input(UInt(32.W))
        val r_data  = Output(UInt(32.W))
        val write = Input(Bool())
        val w_addr  = Input(UInt(32.W))
        val w_data  = Input(UInt(32.W))
        val w_strb  = Input(UInt(4.W))
    })
    setInline("SRAM_BRIDGE.v",
    """module sram_bridge(
      |    input  clock,
      |    input  read,
      |    input  [31:0] r_addr,
      |    output [31:0] r_data,
      |    input  write,
      |    input  [31:0] w_addr,
      |    input  [31:0] w_data,
      |    input  [3:0]  w_strb
      |);
      |
      |import "DPI-C" function void sram_read (input bit [31:0] addr, output bit [31:0] data);
      |import "DPI-C" function void sram_write (input bit [31:0] addr, input bit [31:0] data, input bit [3:0] mask);
      |
      |    always @(posedge clock) begin
      |        if (read) begin
      |            sram_read(r_addr, r_data);
      |        end
      |        if (write) begin
      |            sram_write(w_addr, w_data, w_strb);
      |        end
      |    end
      |
      |endmodule
    """.stripMargin)
}

class SRAM(address: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
    val beatBytes = 4
    val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
            address       = address,
            executable    = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead  = TransferSizes(1, beatBytes),
            interleavedId = Some(0))
        ),
        beatBytes  = beatBytes)))
        
    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {

        val AXI = node.in(0)._1
        
        AXI.r.bits.id := RegEnable(AXI.ar.bits.id, AXI.ar.fire)
        AXI.b.bits.id := RegEnable(AXI.aw.bits.id, AXI.aw.fire)
        
        val s_wait_addr :: s_burst :: s_wait_resp :: Nil = Enum(3)
        
        val state_r = RegInit(s_wait_addr)
        val state_w = RegInit(s_wait_addr)
        
        val read_burst_counter = RegEnable(AXI.ar.bits.len, AXI.ar.fire)
        val write_burst_counter = RegEnable(AXI.aw.bits.len, AXI.aw.fire)

        val read_addr = RegEnable(AXI.ar.bits.addr + 4.U, AXI.ar.fire)

        when(AXI.r.fire) {
            read_burst_counter := read_burst_counter - 1.U
            read_addr := read_addr + 4.U
        }
        when(AXI.w.fire) {
            write_burst_counter := write_burst_counter - 1.U
        }
        
        AXI.r.bits.last := read_burst_counter === 0.U

        state_r := MuxLookup(state_r, s_wait_addr)(
            Seq(
                s_wait_addr -> Mux(AXI.ar.valid, s_burst, s_wait_addr),
                s_burst     -> Mux(read_burst_counter === 0.U, s_wait_addr, s_burst),
            )
        )

        state_w := MuxLookup(state_w, s_wait_addr)(
            Seq(
                s_wait_addr -> Mux(AXI.aw.fire, s_burst, s_wait_addr),
                s_burst     -> Mux(write_burst_counter === 0.U,  s_wait_resp, s_burst),
                s_wait_resp -> Mux(AXI.b.fire, s_wait_addr, s_wait_resp)
            )
        )

        AXI.r.valid  := state_r === s_burst
        AXI.ar.ready := state_r === s_wait_addr

        AXI.aw.ready := state_w === s_wait_addr
        AXI.w.ready  := state_w === s_burst
        AXI.b.valid  := state_w === s_wait_resp

        val bridge = Module(new sram_bridge)
        bridge.io.clock := clock
        bridge.io.read := (state_r === s_burst) || (AXI.ar.fire)
        bridge.io.r_addr  := Mux(AXI.ar.fire, AXI.ar.bits.addr, read_addr)
        AXI.r.bits.data := bridge.io.r_data
        AXI.r.bits.resp := "b0".U

        bridge.io.write := state_w === s_burst
        bridge.io.w_addr  := RegEnable(AXI.aw.bits.addr, AXI.aw.fire)
        bridge.io.w_data  := AXI.w.bits.data
        bridge.io.w_strb  := AXI.w.bits.strb
        AXI.b.bits.resp := "b0".U

        val state_rcache = RegInit(s_wait_addr)
        state_rcache := state_r
        val state_wcache = RegInit(s_wait_addr)
        state_wcache := state_w
    }
}
