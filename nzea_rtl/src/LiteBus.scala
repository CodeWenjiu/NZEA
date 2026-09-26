package nzea_rtl

import me.jiuyang.zaozi.{ConstructorApi, TypeImpl}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*
import me.jiuyang.stdlib.Decoupled

/** Request payload: addr, wdata, wen, wstrb, user, id. */
class LiteReq(addrWidth: Int, dataWidth: Int, userWidth: Int, idWidth: Int)(using TypeImpl, ConstructorApi)
    extends Bundle:
  val addr = Aligned(UInt(addrWidth))
  val wdata = Aligned(UInt(dataWidth))
  val wen = Aligned(Bool())
  val wstrb = Aligned(UInt(dataWidth / 8))
  val user = Aligned(UInt(userWidth))
  val id = Aligned(UInt(idWidth))

/** Response payload: data, user, id. */
class LiteResp(dataWidth: Int, userWidth: Int, idWidth: Int)(using TypeImpl, ConstructorApi) extends Bundle:
  val data = Aligned(UInt(dataWidth))
  val user = Aligned(UInt(userWidth))
  val id = Aligned(UInt(idWidth))

/** Read-only request payload: address + user + id, no write fields. */
class LiteReqRO(addrWidth: Int, userWidth: Int, idWidth: Int)(using TypeImpl, ConstructorApi) extends Bundle:
  val addr = Aligned(UInt(addrWidth))
  val user = Aligned(UInt(userWidth))
  val id = Aligned(UInt(idWidth))

/** Interface widths, shared by the bus flavours and the fabric modules built on them. */
trait LiteBusLike:
  def addrWidth: Int
  def dataWidth: Int
  def userWidth: Int
  def idWidth: Int

/** Unified read-write bus with a request ID for outstanding support.
  *
  * The channels are plain `Decoupled`: cancellation is a master-side contract (tag/epoch matching) — the transport
  * carries no flush/rollback semantics. Declared from the *master's* point of view, so a slave wraps it in `Flipped`.
  */
class LiteBusRW(val addrWidth: Int, val dataWidth: Int, val userWidth: Int, val idWidth: Int)(using
    TypeImpl,
    ConstructorApi
) extends Bundle
    with LiteBusLike:
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

  val req = Aligned(Decoupled(new LiteReq(addrWidth, dataWidth, userWidth, idWidth)))
  val resp = Flipped(Decoupled(new LiteResp(dataWidth, userWidth, idWidth)))

/** Read-only bus: the request has no write fields, so "never writes" is a type-level guarantee instead of a `wen :=
  * false.B` convention. Used by fetch-side masters (I-Fetch, I-Cache).
  */
class LiteBusRO(val addrWidth: Int, val dataWidth: Int, val userWidth: Int, val idWidth: Int)(using
    TypeImpl,
    ConstructorApi
) extends Bundle
    with LiteBusLike:
  require(userWidth >= 1, s"userWidth must be >= 1, got $userWidth")
  require(idWidth >= 1, s"idWidth must be >= 1, got $idWidth")

  val req = Aligned(Decoupled(new LiteReqRO(addrWidth, userWidth, idWidth)))
  val resp = Flipped(Decoupled(new LiteResp(dataWidth, userWidth, idWidth)))
