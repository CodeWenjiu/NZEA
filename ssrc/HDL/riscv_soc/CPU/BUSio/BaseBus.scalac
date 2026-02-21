package riscv_soc.bus

// Basic Simple Bus for CPU Output
// follow the requirements below:
// - valid ready shake-hand
// - use same data width with cpu
// - no burst transfer
// - no transfer id
// - no outstanding request
// - addr channel is not lock for data channel for pipeline

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import riscv_soc.CPUAXI4BundleParameters

object BaseBusSize extends ChiselEnum {
    val Byte,
        HalfWord,
        Word
        = Value

    def ToMask(size: BaseBusSize.Type, addr: UInt): UInt = {
        MuxLookup(size, "b1111".U)(Seq(
            BaseBusSize.Byte     -> "b0001".U,
            BaseBusSize.HalfWord -> "b0011".U,
            BaseBusSize.Word     -> "b1111".U
        )) << addr(1, 0)
    }

    def WriteDataFix(size: BaseBusSize.Type, data: UInt): UInt = {
        MuxLookup(size, data)(Seq(
            BaseBusSize.Byte     -> Fill(4, data(7, 0)),
            BaseBusSize.HalfWord -> Fill(2, data(15, 0)),
            BaseBusSize.Word     -> data
        ))
    }

    def ReadDataFix(size: BaseBusSize.Type, data: UInt, bias: UInt, sig_ext: Bool): UInt = {
        val data_select = MuxLookup(bias, data)(Seq(
            "b00".U -> data,
            "b01".U -> data(15, 8),
            "b10".U -> data(31, 16),
            "b11".U -> data(31, 24)
        ))

        MuxLookup(size, data_select)(Seq(
            BaseBusSize.Byte     -> Cat(Fill(24, Mux(sig_ext, data_select(7), false.B)), data_select(7,  0)),
            BaseBusSize.HalfWord -> Cat(Fill(16, Mux(sig_ext, data_select(7), false.B)), data_select(15, 0)),
            BaseBusSize.Word     -> data_select
        ))
    }
}

class BaseBusBuffer(user_bits: Int = 0) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(new BaseBus(user_bits))
        val out = new BaseBus(user_bits)
    })
    val s_idle :: s_work :: Nil = Enum(2)
    val state = RegInit(s_idle)

    io.in.req.ready := (state === s_idle)
    state := MuxLookup(state, s_idle)(Seq(
        s_idle -> Mux(io.in.req.fire, s_work, s_idle),
        s_work -> Mux(io.out.req.fire, s_idle, s_work)
    ))
    val bufferd_req = RegEnable(io.in.req.bits, io.in.req.fire)

    io.out.req.bits := bufferd_req
    io.out.req.valid := (state === s_work)
    io.out.resp <> io.in.resp
}

class BaseBusToAXI(user_bits: Int = 0, id: Int) extends Module {
    val io = IO(new Bundle {
        val in = Flipped(new BaseBus(user_bits))
        val out = new AXI4Bundle(CPUAXI4BundleParameters())
    })

    val axi = Wire(chiselTypeOf(io.out))
    val addr = io.in.req.bits.addr

    io.in.req.ready := Mux(io.in.req.bits.wen, axi.aw.ready, axi.ar.ready)

    val rvalid = io.in.req.valid && !io.in.req.bits.wen

    axi.ar.valid        := rvalid
    axi.ar.bits.addr    := addr
    axi.ar.bits.id      := id.U
    axi.ar.bits.size    := io.in.req.bits.size.asUInt
    axi.ar.bits.len     := 0.U
    axi.ar.bits.burst   := 0.U // no burst transfer
    axi.ar.bits.lock    := 0.U
    axi.ar.bits.cache   := 0.U
    axi.ar.bits.prot    := 0.U
    axi.ar.bits.qos     := 0.U

    axi.r.ready             := io.in.resp.ready && (axi.r.bits.id === id.U)

    io.in.resp.bits.rdata := axi.r.bits.data
    
    io.in.resp.valid         := axi.r.valid || axi.b.valid
    if (user_bits > 0) {
        io.in.resp.bits.user.get := RegEnable(io.in.req.bits.user.get, io.in.req.fire)
    }

    val wvalid = io.in.req.valid && io.in.req.bits.wen

    val s_idle :: s_wbusy :: Nil = Enum(2)
    val w_state = RegInit(s_idle)

    w_state := MuxCase(w_state, Seq(
        (axi.aw.fire && !axi.w.fire) -> s_wbusy,
        (axi.w.fire) -> s_idle
    ))

    axi.aw.bits <> axi.ar.bits
    axi.ar.bits.id      := id.U
    axi.aw.valid        := wvalid && (w_state === s_idle)
    axi.aw.bits.addr    := addr
    
    axi.w.valid         := wvalid || (w_state === s_wbusy)
    axi.w.bits.data     := io.in.req.bits.wdata
    axi.w.bits.strb     := BaseBusSize.ToMask(io.in.req.bits.size, addr)
    axi.w.bits.last     := true.B

    axi.b.ready         := io.in.resp.ready  && (axi.b.bits.id === id.U)
    
    axi <> io.out
}

class BaseBus(user_bits: Int = 0) extends Bundle {
    val req = Decoupled(new Bundle {
        val addr = UInt(32.W)
        val size = BaseBusSize()
        val wen = Bool()
        val wdata = UInt(32.W)
        val user = if (user_bits > 0) Some(UInt(user_bits.W)) else null
    })

    val resp = Flipped(Decoupled(new Bundle {
        val rdata = UInt(32.W)
        val user = if (user_bits > 0) Some(UInt(user_bits.W)) else null
    }))

    def bufferd = {
        val bufferd = Module(new BaseBusBuffer(user_bits))
        bufferd.io.in <> this
        bufferd.io.out
    }

    def toAXI(id: Int) = {
        val toAXI = Module(new BaseBusToAXI(user_bits, id))
        toAXI.io.in <> this
        toAXI.io.out
    }
}
