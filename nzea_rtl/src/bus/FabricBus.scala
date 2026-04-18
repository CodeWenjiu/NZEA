package nzea_rtl

import chisel3._
import chisel3.util.{Cat, log2Ceil, MuxCase}

/** Address window for FabricBus decode. */
case class FabricAddrRange(base: BigInt, size: BigInt) {
  require(base >= 0, s"base must be >= 0, got $base")
  require(size > 0, s"size must be > 0, got $size")
  val endExclusive: BigInt = base + size
}

/** FabricBus request payload: addr, wdata, wen, wstrb, user, id. */
class FabricReq(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val addr  = UInt(addrWidth.W)
  val wdata = UInt(dataWidth.W)
  val wen   = Bool()
  val wstrb = UInt((dataWidth / 8).W)
  val user  = UInt(userWidth.W)
  val id    = UInt(idWidth.W)
}

/** FabricBus response payload: data, user, id. */
class FabricResp(dataWidth: Int, userWidth: Int, idWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
  val user = UInt(userWidth.W)
  val id   = UInt(idWidth.W)
}

trait FabricBusLike { self: Bundle =>
  def addrWidth: Int
  def dataWidth: Int
  def userWidth: Int
  def idWidth: Int
}

/** Fabric read-write bus (supports outstanding via request ID). */
class FabricBusRW(
  val addrWidth: Int,
  val dataWidth: Int,
  val userWidth: Int,
  val idWidth: Int
) extends Bundle
    with FabricBusLike {
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")
  val req = new PipeIO(new FabricReq(addrWidth, dataWidth, userWidth, idWidth))
  val resp = Flipped(new PipeIO(new FabricResp(dataWidth, userWidth, idWidth)))
}

private object FabricBusCast {
  def castWidth(x: UInt, inWidth: Int, outWidth: Int): UInt = {
    if (outWidth == inWidth) x
    else if (outWidth > inWidth) {
      if (inWidth == 0) 0.U(outWidth.W) else Cat(0.U((outWidth - inWidth).W), x)
    } else {
      if (outWidth == 0) 0.U(0.W)
      else if (inWidth == 0) 0.U(outWidth.W)
      else x(outWidth - 1, 0)
    }
  }
}

/** Bridge LiteBusRO master into FabricBusRW master.
  * Each accepted request gets an auto-assigned ID (monotonic counter).
  */
class LiteBusROToFabricRW(
  addrWidth: Int,
  dataWidth: Int,
  liteUserWidth: Int,
  fabricUserWidth: Int,
  idWidth: Int
) extends Module {
  private val liteBusType = new LiteBusRO(addrWidth, dataWidth, liteUserWidth)
  private val fabricBusType = new FabricBusRW(addrWidth, dataWidth, fabricUserWidth, idWidth)
  private val idCounter = RegInit(0.U(idWidth.W))

  val io = IO(new Bundle {
    val in = Flipped(liteBusType.cloneType)
    val out = fabricBusType.cloneType
  })

  io.out.req.valid := io.in.req.valid
  io.out.req.bits.addr := io.in.req.bits.addr
  io.out.req.bits.wdata := 0.U
  io.out.req.bits.wen := false.B
  io.out.req.bits.wstrb := 0.U
  io.out.req.bits.user := FabricBusCast.castWidth(io.in.req.bits.user, liteUserWidth, fabricUserWidth)
  io.out.req.bits.id := idCounter
  io.in.req.ready := io.out.req.ready
  io.in.req.flush := io.out.req.flush

  when(io.out.req.fire) {
    idCounter := idCounter + 1.U
  }

  io.in.resp.valid := io.out.resp.valid
  io.in.resp.bits.data := io.out.resp.bits.data
  io.in.resp.bits.user := FabricBusCast.castWidth(io.out.resp.bits.user, fabricUserWidth, liteUserWidth)
  io.out.resp.ready := io.in.resp.ready
  io.out.resp.flush := io.in.resp.flush
}

/** Bridge LiteBusRW master into FabricBusRW master.
  * Each accepted request gets an auto-assigned ID (monotonic counter).
  */
class LiteBusRWToFabricRW(
  addrWidth: Int,
  dataWidth: Int,
  liteUserWidth: Int,
  fabricUserWidth: Int,
  idWidth: Int
) extends Module {
  private val liteBusType = new LiteBusRW(addrWidth, dataWidth, liteUserWidth)
  private val fabricBusType = new FabricBusRW(addrWidth, dataWidth, fabricUserWidth, idWidth)
  private val idCounter = RegInit(0.U(idWidth.W))

  val io = IO(new Bundle {
    val in = Flipped(liteBusType.cloneType)
    val out = fabricBusType.cloneType
  })

  io.out.req.valid := io.in.req.valid
  io.out.req.bits.addr := io.in.req.bits.addr
  io.out.req.bits.wdata := io.in.req.bits.wdata
  io.out.req.bits.wen := io.in.req.bits.wen
  io.out.req.bits.wstrb := io.in.req.bits.wstrb
  io.out.req.bits.user := FabricBusCast.castWidth(io.in.req.bits.user, liteUserWidth, fabricUserWidth)
  io.out.req.bits.id := idCounter
  io.in.req.ready := io.out.req.ready
  io.in.req.flush := io.out.req.flush

  when(io.out.req.fire) {
    idCounter := idCounter + 1.U
  }

  io.in.resp.valid := io.out.resp.valid
  io.in.resp.bits.data := io.out.resp.bits.data
  io.in.resp.bits.user := FabricBusCast.castWidth(io.out.resp.bits.user, fabricUserWidth, liteUserWidth)
  io.out.resp.ready := io.in.resp.ready
  io.out.resp.flush := io.in.resp.flush
}

/** Bridge FabricBusRW slave-side port into LiteBusRW slave-side port.
  * Request ID is dropped on Lite wires, but tracked internally and restored on response.
  * Assumes Lite side keeps request/response ordering.
  */
class FabricRWToLiteRW(
  addrWidth: Int,
  dataWidth: Int,
  fabricUserWidth: Int,
  idWidth: Int,
  liteUserWidth: Int,
  outstandingDepth: Int = 16
) extends Module {
  require(outstandingDepth >= 1, s"outstandingDepth must be >= 1, got $outstandingDepth")
  private val fabricBusType = new FabricBusRW(addrWidth, dataWidth, fabricUserWidth, idWidth)
  private val liteBusType = new LiteBusRW(addrWidth, dataWidth, liteUserWidth)
  private val ptrWidth = log2Ceil(outstandingDepth.max(2))
  private val cntWidth = log2Ceil(outstandingDepth + 1)

  private def wrapInc(x: UInt): UInt =
    if (outstandingDepth <= 1) 0.U else Mux(x === (outstandingDepth - 1).U, 0.U, x + 1.U)

  val io = IO(new Bundle {
    val in = Flipped(fabricBusType.cloneType)
    val out = liteBusType.cloneType
  })

  // Track IDs for in-flight requests across Fabric->Lite conversion.
  val idQ = Reg(Vec(outstandingDepth, UInt(idWidth.W)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(cntWidth.W))
  val canEnq = count =/= outstandingDepth.U
  val canDeq = count =/= 0.U
  val flush = io.in.resp.flush || io.out.req.flush

  io.out.req.valid := io.in.req.valid
  io.out.req.bits.addr := io.in.req.bits.addr
  io.out.req.bits.wdata := io.in.req.bits.wdata
  io.out.req.bits.wen := io.in.req.bits.wen
  io.out.req.bits.wstrb := io.in.req.bits.wstrb
  io.out.req.bits.user := FabricBusCast.castWidth(io.in.req.bits.user, fabricUserWidth, liteUserWidth)
  io.in.req.ready := io.out.req.ready && canEnq && !flush
  io.in.req.flush := io.out.req.flush

  io.in.resp.valid := io.out.resp.valid && canDeq && !flush
  io.in.resp.bits.data := io.out.resp.bits.data
  io.in.resp.bits.user := FabricBusCast.castWidth(io.out.resp.bits.user, liteUserWidth, fabricUserWidth)
  io.in.resp.bits.id := Mux(canDeq, idQ(head), 0.U)
  io.out.resp.ready := io.in.resp.ready && canDeq && !flush
  io.out.resp.flush := io.in.resp.flush

  val reqFire = io.out.req.valid && io.out.req.ready
  val respFire = io.out.resp.valid && io.out.resp.ready

  when(io.out.resp.valid && !canDeq && !flush) {
    assert(false.B, "FabricRWToLiteRW: response observed without pending request ID")
  }
  when(io.out.req.valid && io.out.req.ready && !canEnq && !flush) {
    assert(false.B, "FabricRWToLiteRW: request accepted while ID queue full")
  }

  when(flush) {
    head := 0.U
    tail := 0.U
    count := 0.U
  }.otherwise {
    when(reqFire) {
      idQ(tail) := io.in.req.bits.id
      tail := wrapInc(tail)
    }
    when(respFire) {
      head := wrapInc(head)
    }
    count := MuxCase(
      count,
      Seq(
        (reqFire && !respFire) -> (count + 1.U),
        (!reqFire && respFire) -> (count - 1.U)
      )
    )
  }
}
